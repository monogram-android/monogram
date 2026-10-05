use crate::*;


    #[test]
    fn dns_cache_rotates_and_evicts_without_crossing_proxy_scope() {
        let host = "cache-scope.invalid";
        let addresses = [
            "127.0.0.1:10001".parse().unwrap(),
            "127.0.0.1:10002".parse().unwrap(),
        ];
        TcpConnection::cache_addresses_with_ttl(host, 443, false, &addresses, DNS_CACHE_TTL);
        TcpConnection::cache_addresses_with_ttl(host, 443, true, &addresses, DNS_CACHE_TTL);
        assert_eq!(
            TcpConnection::cached_resolution(host, 443, true).unwrap(),
            vec![addresses[1], addresses[0]]
        );
        assert_eq!(
            TcpConnection::cached_resolution(host, 443, true).unwrap(),
            addresses
        );
        TcpConnection::evict_cached_address(host, 443, true, addresses[0]);
        assert_eq!(
            TcpConnection::cached_resolution(host, 443, true).unwrap(),
            vec![addresses[1]]
        );
        assert_eq!(
            TcpConnection::cached_resolution(host, 443, false)
                .unwrap()
                .len(),
            2
        );
        TcpConnection::evict_cached_addresses(host, 443, true);
        TcpConnection::evict_cached_addresses(host, 443, false);
    }

    #[test]
    fn dns_expired_answers_are_removed() {
        let host = "cache-expiry.invalid";
        let key = TcpConnection::dns_cache_key(host, 443, true);
        DNS_CACHE.lock().unwrap().insert(
            key.clone(),
            CachedAddresses {
                addresses: vec!["127.0.0.1:443".parse().unwrap()],
                expires_at: Instant::now() - Duration::from_secs(1),
                next: 0,
            },
        );
        assert!(TcpConnection::cached_resolution(host, 443, true).is_none());
        assert!(!DNS_CACHE.lock().unwrap().contains_key(&key));
    }

    #[test]
    fn proxy_connection_uses_cached_addresses_without_resolving_target() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut header = Vec::new();
            let mut byte = [0_u8; 1];
            while !header.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                header.push(byte[0]);
            }
            assert!(header.starts_with(b"CONNECT target.invalid:443 HTTP/1.1\r\n"));
            stream
                .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                .unwrap();
        });
        let proxy = ProxyConfig {
            kind: ProxyKind::Http,
            host: "cached-proxy.invalid".into(),
            port: address.port(),
            username: None,
            password: None,
            secret: None,
        };
        TcpConnection::cache_addresses_with_ttl(
            &proxy.host,
            proxy.port,
            true,
            &[address],
            DNS_CACHE_TTL,
        );
        TcpConnection::connect("target.invalid:443", Duration::from_secs(2), Some(&proxy)).unwrap();
        server.join().unwrap();
        TcpConnection::evict_cached_addresses(&proxy.host, proxy.port, true);
    }

    #[test]
    fn http_transport_serializes_batched_sends_in_fifo_order() {
        use std::net::TcpListener;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            for index in 0..3_u8 {
                stream
                    .set_read_timeout(Some(Duration::from_secs(3)))
                    .unwrap();
                let mut head = Vec::new();
                let mut byte = [0_u8; 1];
                while !head.ends_with(b"\r\n\r\n") {
                    stream.read_exact(&mut byte).unwrap();
                    head.push(byte[0]);
                }
                assert!(
                    String::from_utf8(head)
                        .unwrap()
                        .contains("Content-Length: 72\r\n")
                );
                let mut body = [0_u8; 72];
                stream.read_exact(&mut body).unwrap();
                assert_eq!(body, [7 + index; 72]);
                stream
                    .set_read_timeout(Some(Duration::from_millis(50)))
                    .unwrap();
                let error = stream.peek(&mut byte).unwrap_err();
                assert!(matches!(
                    error.kind(),
                    std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock
                ));
                stream
                    .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n")
                    .unwrap();
                stream.write_all(&[index + 1; 4]).unwrap();
            }
        });
        let connection =
            TcpConnection::connect(&address.to_string(), Duration::from_secs(3), None).unwrap();
        let mut transport = connection
            .into_transport("dc.example:80", TransportMode::Http, Some(2), None)
            .unwrap();
        for index in 0..3_u8 {
            let packet = tellers_mtproto_transport::PaddedIntermediate::default()
                .encode_with_padding(&[7 + index; 72], &[42; 15])
                .unwrap();
            transport.send(&packet).unwrap();
        }
        for index in 0..3_u8 {
            let mut received = Vec::new();
            let mut buffer = [0_u8; 3];
            while received.len() < 8 {
                let count = transport.receive(&mut buffer).unwrap();
                assert!(count > 0);
                received.extend_from_slice(&buffer[..count]);
            }
            assert_eq!(&received[..4], &4_u32.to_le_bytes());
            assert_eq!(&received[4..], &[index + 1; 4]);
        }
        server.join().unwrap();
    }

    #[test]
    fn http_transport_bounds_queue_and_drops_it_on_close() {
        use std::net::TcpListener;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let mut received = Vec::new();
            stream.read_to_end(&mut received).unwrap();
            assert_eq!(
                received
                    .windows(9)
                    .filter(|window| *window == b"POST /api")
                    .count(),
                1
            );
        });
        let connection =
            TcpConnection::connect(&address.to_string(), Duration::from_secs(3), None).unwrap();
        let mut http = connection.into_http("dc.example:80").unwrap();
        let packet = [3, 0, 0, 0, b'a', b'b', b'c'];
        http.send(&packet).unwrap();
        for _ in 0..MAX_HTTP_QUEUED_REQUESTS {
            http.send(&packet).unwrap();
        }
        assert!(http.send(&packet).is_err());
        http.close().unwrap();
        assert!(http.send(&packet).is_err());
        assert!(http.receive(&mut [0_u8; 8]).is_err());
        server.join().unwrap();
    }
    #[test]
    fn http_transport_sends_unpadded_envelope_and_decodes_chunked_response() {
        use std::net::TcpListener;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let mut head = Vec::new();
            let mut byte = [0_u8; 1];
            while !head.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                head.push(byte[0]);
            }
            assert!(
                String::from_utf8(head)
                    .unwrap()
                    .contains("Content-Length: 72\r\n")
            );
            let mut body = [0_u8; 72];
            stream.read_exact(&mut body).unwrap();
            assert_eq!(body, [7_u8; 72]);
            stream.write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n").unwrap();
        });
        let connection =
            TcpConnection::connect(&address.to_string(), Duration::from_secs(3), None).unwrap();
        let mut transport = connection
            .into_transport("dc.example:80", TransportMode::Http, Some(2), None)
            .unwrap();
        let packet = tellers_mtproto_transport::PaddedIntermediate::default()
            .encode_with_padding(&[7; 72], &[42; 15])
            .unwrap();
        transport.send(&packet).unwrap();
        let mut received = Vec::new();
        let mut buffer = [0_u8; 2];
        while received.len() < 9 {
            let count = transport.receive(&mut buffer).unwrap();
            assert!(count > 0);
            received.extend_from_slice(&buffer[..count]);
        }
        assert_eq!(received, b"\x05\0\0\0abcde");
        server.join().unwrap();
    }
    #[test]
    fn http_payload_strips_transport_padding_but_preserves_encrypted_envelope() {
        use tellers_mtproto_transport::PaddedIntermediate;
        let framing = PaddedIntermediate::default();
        let mut unencrypted = vec![0_u8; 20];
        unencrypted[8..16].copy_from_slice(&1_u64.to_le_bytes());
        unencrypted[16..20].copy_from_slice(&4_u32.to_le_bytes());
        unencrypted.extend_from_slice(b"body");
        let encrypted = vec![7_u8; 24 + 48];
        for padding_length in 0..16 {
            let padding = vec![42_u8; padding_length];
            for envelope in [&unencrypted, &encrypted] {
                let packet = framing.encode_with_padding(envelope, &padding).unwrap();
                assert_eq!(telegram_http_payload(&packet).unwrap(), envelope.as_slice());
            }
        }
        assert!(telegram_http_payload(&[1, 0, 0, 0]).is_err());
        assert!(telegram_http_payload(&[0, 0, 0, 0, 42]).is_err());
    }

    #[test]
    fn http_response_headers_reject_ambiguous_or_malformed_framing() {
        for header in [
            "NOTHTTP 200 OK\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 0200 OK\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: +1\r\n\r\n",
            "HTTP/1.1 200 OK\r\n Content-Length: 1\r\n\r\n",
            "HTTP/1.1 200 OK\r\nInvalid header\r\n\r\n",
        ] {
            assert!(
                parse_http_response_head(header.as_bytes()).is_err(),
                "{header:?}"
            );
        }
    }

    #[test]
    fn http_chunked_body_preserves_state_across_every_byte_boundary() {
        let wire = b"3;fixture=yes\r\nabc\r\n2\r\nde\r\n0\r\nX-Fixture: done\r\n\r\n";
        let mut decoder = HttpChunkDecoder::default();
        for length in 0..wire.len() {
            assert!(decoder.decode(&wire[..length]).unwrap().is_none());
        }
        let (body, consumed) = decoder.decode(wire).unwrap().unwrap();
        assert_eq!(body, b"abcde");
        assert_eq!(consumed, wire.len());
    }

    #[test]
    fn http_chunked_body_rejects_overflow_and_invalid_delimiters() {
        for wire in [
            b"1000001\r\n".as_slice(),
            b"+1\r\na\r\n".as_slice(),
            b"1\r\naXX".as_slice(),
            b"0\r\nContent-Length: 1\r\n\r\n".as_slice(),
        ] {
            assert!(HttpChunkDecoder::default().decode(wire).is_err());
        }
    }
    #[test]
    fn fake_tls_rejects_unauthenticated_server_response() {
        let mut response = vec![0x16, 3, 3, 0, 38, 2, 0, 0, 34, 3, 3];
        response.extend_from_slice(&[0_u8; 32]);
        response.extend_from_slice(&[0x17, 3, 3, 0, 1, 0]);
        let error =
            verify_fake_tls_response(&mut std::io::Cursor::new(response), &[7; 32], &[8; 16])
                .unwrap_err();
        assert!(error.to_string().contains("authentication failed"));
    }

    #[test]
    fn fake_tls_client_hello_authenticates_random_and_advertises_domain() {
        let before = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs() as u32;
        let first = fake_tls_client_hello("example.com", &[7; 16]).unwrap();
        let second = fake_tls_client_hello("example.com", &[7; 16]).unwrap();
        let after = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs() as u32;
        let mut unsigned = first.clone();
        unsigned[11..43].fill(0);
        let digest = hmac_sha256(&[7; 16], &unsigned);
        assert_eq!(&first[11..39], &digest[..28]);
        let timestamp = u32::from_le_bytes(first[39..43].try_into().unwrap())
            ^ u32::from_le_bytes(digest[28..32].try_into().unwrap());
        assert!((before..=after).contains(&timestamp));
        assert_eq!(&first[..5], &[0x16, 3, 1, first[3], first[4]]);
        assert_ne!(&first[11..43], &[0_u8; 32]);
        assert_ne!(&first[11..43], &second[11..43]);
        assert!(
            first
                .windows(b"example.com".len())
                .any(|window| window == b"example.com")
        );
    }

    #[test]
    fn proxy_debug_redacts_credentials_and_secret() {
        let config = ProxyConfig {
            kind: ProxyKind::Mtproto,
            host: "sensitive-host.example".into(),
            port: 443,
            username: Some("sensitive-user".into()),
            password: Some("sensitive-password".into()),
            secret: Some(ProxySecret {
                key: [9; 16],
                random_padding: true,
                fake_tls_domain: Some("sensitive-domain.example".into()),
            }),
        };
        let debug = format!("{config:?} {:?}", config.secret);
        assert!(!debug.contains("sensitive"));
        assert!(!debug.contains("9, 9"));
    }

    #[test]
    fn socks5_rejects_unoffered_authentication_method() {
        let config = ProxyConfig {
            kind: ProxyKind::Socks5,
            host: "localhost".into(),
            port: 1080,
            username: None,
            password: None,
            secret: None,
        };
        let mut stream =
            std::io::Cursor::new(vec![0_u8; 3].into_iter().chain([5, 2]).collect::<Vec<_>>());
        assert!(socks5_connect(&mut stream, "example.com:443", &config, None).is_err());
    }

    #[test]
    fn rejects_partial_credentials() {
        let c = ProxyConfig {
            kind: ProxyKind::Http,
            host: "proxy.example".into(),
            port: 8080,
            username: Some("user".into()),
            password: None,
            secret: None,
        };
        assert!(c.validate().is_err());
    }

    #[test]
    fn rejects_oversized_credentials() {
        let c = ProxyConfig {
            kind: ProxyKind::Http,
            host: "proxy.example".into(),
            port: 8080,
            username: Some("u".repeat(MAX_PROXY_CREDENTIAL + 1)),
            password: Some("p".repeat(MAX_PROXY_CREDENTIAL + 1)),
            secret: None,
        };
        assert!(c.validate().is_err());
    }

    #[test]
    fn rejects_control_characters_in_credentials() {
        let c = ProxyConfig {
            kind: ProxyKind::Http,
            host: "proxy.example".into(),
            port: 8080,
            username: Some("user\r\nInjected: header".into()),
            password: Some("pass".into()),
            secret: None,
        };
        assert!(c.validate().is_err());
    }
    #[test]
    fn parses_fake_tls_secret() {
        let s =
            ProxySecret::parse("ee070707070707070707070707070707076578616d706c652e636f6d").unwrap();
        assert!(s.random_padding);
        assert_eq!(s.fake_tls_domain.as_deref(), Some("example.com"));
    }
    #[test]
    fn classic_secrets_preserve_prefix_like_key_bytes() {
        for first_byte in [0xdd, 0xee] {
            let mut key = [7_u8; 16];
            key[0] = first_byte;
            let encoded = key
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>();
            let parsed = ProxySecret::parse(&encoded).unwrap();
            assert_eq!(parsed.key, key);
            assert!(!parsed.random_padding);
            assert_eq!(parsed.fake_tls_domain, None);
            let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(key);
            assert_eq!(ProxySecret::parse(&encoded).unwrap(), parsed);
        }
    }

    #[test]
    fn prefixed_secrets_require_their_complete_payload() {
        let padded = ProxySecret::parse("dd07070707070707070707070707070707").unwrap();
        assert_eq!(padded.key, [7_u8; 16]);
        assert!(padded.random_padding);
        assert_eq!(padded.fake_tls_domain, None);
        for encoded in [
            "070707070707070707070707070707",
            "0707070707070707070707070707070707aa",
            "dd07070707070707070707070707070707aa",
            "ee07070707070707070707070707070707",
        ] {
            assert!(ProxySecret::parse(encoded).is_err());
        }
    }

    #[test]
    fn resolves_literals_without_dns() {
        let addresses = TcpConnection::resolve("127.0.0.1", 443).unwrap();
        assert_eq!(addresses[0], "127.0.0.1:443".parse().unwrap());
        let addresses = TcpConnection::resolve("::1", 443).unwrap();
        assert_eq!(addresses[0], "[::1]:443".parse().unwrap());
    }

    #[test]
    fn fake_tls_domain_has_a_bounded_ascii_payload() {
        let mut secret = vec![0xee];
        secret.extend_from_slice(&[7; 16]);
        let domain = format!("{}.{}.{}", "a".repeat(63), "b".repeat(63), "c".repeat(54));
        secret.extend_from_slice(domain.as_bytes());
        let encode = |bytes: &[u8]| base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes);
        assert!(ProxySecret::parse(&encode(&secret)).is_ok());
        secret.push(b'a');
        assert!(ProxySecret::parse(&encode(&secret)).is_err());
        secret.truncate(17);
        secret.extend_from_slice("é.example".as_bytes());
        assert!(ProxySecret::parse(&encode(&secret)).is_err());
    }

    #[test]
    fn socks5_no_auth_prefers_ip_target() {
        use std::net::TcpListener;
        use std::thread;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut greeting = [0_u8; 3];
            stream.read_exact(&mut greeting).unwrap();
            assert_eq!(greeting, [5, 1, 0]);
            stream.write_all(&[5, 0]).unwrap();
            let mut request = [0_u8; 4];
            stream.read_exact(&mut request).unwrap();
            assert_eq!(request, [5, 1, 0, 1]);
            let mut address_and_port = [0_u8; 6];
            stream.read_exact(&mut address_and_port).unwrap();
            assert_eq!(&address_and_port[..4], &[127, 0, 0, 2]);
            stream
                .write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 1, 187])
                .unwrap();
        });
        let config = ProxyConfig {
            kind: ProxyKind::Socks5,
            host: "127.0.0.1".into(),
            port: address.port(),
            username: None,
            password: None,
            secret: None,
        };
        TcpConnection::connect("127.0.0.2:443", Duration::from_secs(1), Some(&config)).unwrap();
        server.join().unwrap();
    }

    #[test]
    fn socks5_no_auth_preserves_domain_target_for_proxy_resolution() {
        use std::net::TcpListener;
        use std::thread;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut greeting = [0_u8; 3];
            stream.read_exact(&mut greeting).unwrap();
            assert_eq!(greeting, [5, 1, 0]);
            stream.write_all(&[5, 0]).unwrap();
            let mut request_head = [0_u8; 5];
            stream.read_exact(&mut request_head).unwrap();
            assert_eq!(&request_head[..4], &[5, 1, 0, 3]);
            let domain_length = usize::from(request_head[4]);
            let mut domain_and_port = vec![0_u8; domain_length + 2];
            stream.read_exact(&mut domain_and_port).unwrap();
            assert_eq!(&domain_and_port[..domain_length], b"dc.example");
            assert_eq!(&domain_and_port[domain_length..], &443_u16.to_be_bytes());
            stream
                .write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 1, 187])
                .unwrap();
        });
        let config = ProxyConfig {
            kind: ProxyKind::Socks5,
            host: "127.0.0.1".into(),
            port: address.port(),
            username: None,
            password: None,
            secret: None,
        };
        TcpConnection::connect("dc.example:443", Duration::from_secs(1), Some(&config)).unwrap();
        server.join().unwrap();
    }

    #[test]
    fn socks5_username_password_negotiates() {
        use std::net::TcpListener;
        use std::thread;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut greeting = [0_u8; 4];
            stream.read_exact(&mut greeting).unwrap();
            assert_eq!(greeting, [5, 2, 0, 2]);
            stream.write_all(&[5, 2]).unwrap();
            let mut auth_header = [0_u8; 2];
            stream.read_exact(&mut auth_header).unwrap();
            let mut credentials = vec![0_u8; usize::from(auth_header[1]) + 1];
            stream.read_exact(&mut credentials).unwrap();
            let password_length = usize::from(credentials[usize::from(auth_header[1])]);
            let mut password = vec![0_u8; password_length];
            stream.read_exact(&mut password).unwrap();
            assert_eq!(&credentials[..usize::from(auth_header[1])], b"user");
            assert_eq!(password, b"pass");
            stream.write_all(&[1, 0]).unwrap();
            let mut request = [0_u8; 10];
            stream.read_exact(&mut request).unwrap();
            assert_eq!(&request[..4], &[5, 1, 0, 1]);
            stream
                .write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 1, 187])
                .unwrap();
        });
        let config = ProxyConfig {
            kind: ProxyKind::Socks5,
            host: "127.0.0.1".into(),
            port: address.port(),
            username: Some("user".into()),
            password: Some("pass".into()),
            secret: None,
        };
        TcpConnection::connect("127.0.0.1:443", Duration::from_secs(1), Some(&config)).unwrap();
        server.join().unwrap();
    }

    #[test]
    fn hmac_sha256_matches_standard_vector() {
        assert_eq!(
            hmac_sha256(&[7; 16], b"abc"),
            [
                0xd0, 0x63, 0xde, 0x16, 0x30, 0xfd, 0x83, 0x1c, 0xc1, 0x0f, 0x47, 0xf5, 0xc6, 0xcd,
                0x41, 0x3a, 0x20, 0x4a, 0x2d, 0xe6, 0x94, 0x38, 0x9d, 0xf5, 0xc1, 0x71, 0xae, 0x7f,
                0x10, 0x73, 0x33, 0xac,
            ]
        );
    }

    #[test]
    fn mtproto_fake_tls_connection_emits_tls_shaped_prefix() {
        use std::net::TcpListener;
        use std::thread;

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut hello_header = [0_u8; 5];
            stream.read_exact(&mut hello_header).unwrap();
            assert_eq!(&hello_header[..3], &[0x16, 3, 1]);
            let mut hello_body =
                vec![0_u8; u16::from_be_bytes([hello_header[3], hello_header[4]]) as usize];
            stream.read_exact(&mut hello_body).unwrap();
            let mut response = vec![0x16, 3, 3, 0, 38, 2, 0, 0, 34, 3, 3];
            response.extend_from_slice(&[0_u8; 32]);
            response.extend_from_slice(&[0x14, 3, 3, 0, 1, 1, 0x17, 3, 3, 0, 1, 0]);
            let mut authenticated = hello_body[6..38].to_vec();
            authenticated.extend_from_slice(&response);
            response[11..43].copy_from_slice(&hmac_sha256(&[7; 16], &authenticated));
            stream.write_all(&response).unwrap();
            let mut prefix = vec![0_u8; 64 + 6 + 5 + 4];
            stream.read_exact(&mut prefix).unwrap();
            assert_eq!(&prefix[..6], &[0x14, 3, 3, 0, 1, 1]);
            assert_eq!(&prefix[6..11], &[0x17, 3, 3, 0, 68]);
            assert_ne!(&prefix[11..67], &[0_u8; 56]);
            let mut reversed = [0_u8; 64];
            reversed.copy_from_slice(&prefix[11..75]);
            reversed.reverse();
            let mut key_material = reversed[8..40].to_vec();
            key_material.extend_from_slice(&[7; 16]);
            let key = sha256(&key_material);
            let mut cipher = AesCtr::new(&key, &reversed[40..56]).unwrap();
            let mut payload = b"abcdef".to_vec();
            cipher.apply(&mut payload);
            stream.write_all(&[0x17, 3, 3, 0, 6]).unwrap();
            stream.write_all(&payload).unwrap();
        });
        let secret =
            ProxySecret::parse("ee070707070707070707070707070707076578616d706c652e636f6d").unwrap();
        let config = ProxyConfig {
            kind: ProxyKind::Mtproto,
            host: "127.0.0.1".into(),
            port: address.port(),
            username: None,
            password: None,
            secret: Some(secret),
        };
        let connection =
            TcpConnection::connect("dc.example:443", Duration::from_secs(1), Some(&config))
                .unwrap();
        let mut obfuscated = connection.into_obfuscated(None, Some(2)).unwrap();
        obfuscated.send(b"ping").unwrap();
        let mut first = [0_u8; 2];
        assert_eq!(obfuscated.receive(&mut first).unwrap(), 2);
        assert_eq!(&first, b"ab");
        let mut remaining = [0_u8; 4];
        assert_eq!(obfuscated.receive(&mut remaining).unwrap(), 4);
        assert_eq!(&remaining, b"cdef");
        server.join().unwrap();
    }

    #[test]
    fn parses_doh_ipv4_and_ipv6_answers() {
        let body = br#"{"Answer":[{"type":1,"data":"203.0.113.7","TTL":2},{"type":28,"data":"2001:db8::7","TTL":600}]}"#;
        let addresses = parse_doh_addresses(body, 443).unwrap();
        assert_eq!(addresses.ttl, DNS_MIN_TTL);
        assert_eq!(
            addresses.addresses,
            vec![
                "203.0.113.7:443".parse().unwrap(),
                "[2001:db8::7]:443".parse().unwrap(),
            ]
        );
    }

    #[test]
    fn telegram_http_transport_round_trips_a_response() {
        use std::net::TcpListener;
        use std::thread;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut request = Vec::new();
            let mut byte = [0_u8; 1];
            while !request.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            assert!(String::from_utf8_lossy(&request).contains("POST /api HTTP/1.1"));
            stream
                .write_all(
                    b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: keep-alive\r\n\r\nxyz",
                )
                .unwrap();
        });
        let connection = TcpConnection::connect(
            &format!("127.0.0.1:{}", address.port()),
            Duration::from_secs(1),
            None,
        )
        .unwrap();
        let mut http = connection.into_http("dc.example:443").unwrap();
        http.send(&[3, 0, 0, 0, b'a', b'b', b'c']).unwrap();
        let mut output = [0_u8; 7];
        assert_eq!(http.receive(&mut output).unwrap(), 7);
        assert_eq!(&output[..4], &[3, 0, 0, 0]);
        assert_eq!(&output[4..], b"xyz");
        server.join().unwrap();
    }

    #[test]
    fn socket_registration_can_cancel_proxy_negotiation() {
        use std::net::{Shutdown, TcpListener};
        use std::thread;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let (socket_tx, socket_rx) = std::sync::mpsc::channel();
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        let cancelled = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        let worker = thread::spawn(move || {
            let config = ProxyConfig {
                kind: ProxyKind::Socks5,
                host: "127.0.0.1".into(),
                port: address.port(),
                username: None,
                password: None,
                secret: None,
            };
            let result = TcpConnection::connect_controlled(
                "example.com:443",
                Duration::from_secs(30),
                Some(&config),
                |socket| {
                    socket_tx.send(socket.clone()).unwrap();
                    Ok(())
                },
                move || {
                    if worker_cancelled.load(std::sync::atomic::Ordering::Acquire) {
                        Err(ProxyError::Handshake("connection cancelled".into()))
                    } else {
                        Ok(())
                    }
                },
            );
            done_tx.send(result.is_err()).unwrap();
        });
        let (mut peer, _) = listener.accept().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let mut greeting = [0_u8; 3];
        peer.read_exact(&mut greeting).unwrap();
        cancelled.store(true, std::sync::atomic::Ordering::Release);
        socket_rx
            .recv_timeout(Duration::from_secs(2))
            .unwrap()
            .shutdown(Shutdown::Both)
            .unwrap();
        assert!(done_rx.recv_timeout(Duration::from_secs(2)).unwrap());
        worker.join().unwrap();
    }

    #[test]
    fn telegram_http_large_response_survives_small_reads() {
        use std::net::TcpListener;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let mut request = Vec::new();
            let mut byte = [0_u8; 1];
            while !request.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            let mut body = [0_u8; 3];
            stream.read_exact(&mut body).unwrap();
            assert_eq!(&body, b"abc");
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 131072\r\n\r\n")
                .unwrap();
            stream.write_all(&vec![42_u8; 131072]).unwrap();
        });
        let connection =
            TcpConnection::connect(&address.to_string(), Duration::from_secs(3), None).unwrap();
        let mut http = connection.into_http("dc.example:80").unwrap();
        let packet = [3, 0, 0, 0, b'a', b'b', b'c'];
        http.send(&packet).unwrap();
        let mut received = Vec::new();
        let mut buffer = [0_u8; 37];
        while received.len() < 131076 {
            let count = http.receive(&mut buffer).unwrap();
            assert!(count > 0);
            received.extend_from_slice(&buffer[..count]);
        }
        assert_eq!(&received[..4], &131072_u32.to_le_bytes());
        assert!(received[4..].iter().all(|byte| *byte == 42));
        assert!(http.receive(&mut buffer).is_err());
        server.join().unwrap();
    }

    #[test]
    fn http_connect_rejects_non_success_status() {
        use std::net::TcpListener;
        use std::thread;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut bytes = Vec::new();
            let mut chunk = [0_u8; 256];
            while !bytes.windows(4).any(|w| w == b"\r\n\r\n") {
                let n = stream.read(&mut chunk).unwrap();
                bytes.extend_from_slice(&chunk[..n]);
            }
            assert!(bytes.starts_with(b"CONNECT example.com:443 HTTP/1.1\r\n"));
            stream
                .write_all(
                    b"HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\n\r\n",
                )
                .unwrap();
        });
        let config = ProxyConfig {
            kind: ProxyKind::Http,
            host: "127.0.0.1".into(),
            port: address.port(),
            username: None,
            password: None,
            secret: None,
        };
        assert!(
            TcpConnection::connect("example.com:443", Duration::from_secs(1), Some(&config))
                .is_err()
        );
        server.join().unwrap();
    }

    #[test]
    fn http_connect_rejects_malformed_status_line() {
        use std::net::TcpListener;
        use std::thread;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut request = Vec::new();
            let mut byte = [0_u8; 1];
            while !request.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            stream.write_all(b"NOT-HTTP 200\r\n\r\n").unwrap();
        });
        let config = ProxyConfig {
            kind: ProxyKind::Http,
            host: "127.0.0.1".into(),
            port: address.port(),
            username: None,
            password: None,
            secret: None,
        };
        assert!(
            TcpConnection::connect("example.com:443", Duration::from_secs(1), Some(&config))
                .is_err()
        );
        server.join().unwrap();
    }

    #[test]
    fn http_connect_auth_succeeds() {
        use std::net::TcpListener;
        use std::thread;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut bytes = Vec::new();
            let mut chunk = [0_u8; 256];
            while !bytes.windows(4).any(|window| window == b"\r\n\r\n") {
                let count = stream.read(&mut chunk).unwrap();
                bytes.extend_from_slice(&chunk[..count]);
            }
            let request = String::from_utf8(bytes).unwrap();
            assert!(request.starts_with("CONNECT example.com:443 HTTP/1.1\r\n"));
            assert!(!request.contains("Proxy-Authorization:"));
            stream.write_all(b"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic\r\nContent-Length: 0\r\n\r\n").unwrap();
            let mut bytes = Vec::new();
            let mut byte = [0_u8; 1];
            while !bytes.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                bytes.push(byte[0]);
            }
            let request = String::from_utf8(bytes).unwrap();
            assert!(request.contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n"));
            stream
                .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                .unwrap();
        });
        let config = ProxyConfig {
            kind: ProxyKind::Http,
            host: "127.0.0.1".into(),
            port: address.port(),
            username: Some("user".into()),
            password: Some("pass".into()),
            secret: None,
        };
        TcpConnection::connect("example.com:443", Duration::from_secs(1), Some(&config)).unwrap();
        server.join().unwrap();
    }

    #[test]
    fn https_proxy_rejects_plaintext_endpoint() {
        use std::net::TcpListener;
        use std::thread;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let _ = stream.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n");
        });
        let config = ProxyConfig {
            kind: ProxyKind::Https,
            host: "localhost".into(),
            port: address.port(),
            username: None,
            password: None,
            secret: None,
        };
        assert!(
            TcpConnection::connect("example.com:443", Duration::from_secs(1), Some(&config))
                .is_err()
        );
        server.join().unwrap();
    }

    fn exercise_https_certificate(subject: &str, trusted: bool) -> (bool, bool) {
        use rustls::pki_types::PrivatePkcs8KeyDer;
        use rustls::{RootCertStore, ServerConfig, ServerConnection};
        use std::net::TcpListener;

        let certificate = rcgen::generate_simple_self_signed(vec![subject.to_owned()]).unwrap();
        let der = certificate.cert.der().clone();
        let key = PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der());
        let config = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![der.clone()], key.into())
            .unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            socket
                .set_write_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut stream =
                StreamOwned::new(ServerConnection::new(Arc::new(config)).unwrap(), socket);
            let mut header = Vec::new();
            let mut byte = [0_u8; 1];
            while !header.ends_with(b"\r\n\r\n") {
                if stream.read_exact(&mut byte).is_err() {
                    return false;
                }
                header.push(byte[0]);
                assert!(header.len() <= MAX_PROXY_HEADERS);
            }
            assert!(header.starts_with(b"CONNECT example.com:443 HTTP/1.1\r\n"));
            stream
                .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\nping")
                .unwrap();
            stream.flush().unwrap();
            let mut reply = [0_u8; 4];
            stream.read_exact(&mut reply).unwrap();
            assert_eq!(&reply, b"pong");
            true
        });
        let proxy = ProxyConfig {
            kind: ProxyKind::Https,
            host: "127.0.0.1".into(),
            port: address.port(),
            username: None,
            password: None,
            secret: None,
        };
        let result = TcpConnection::connect_with_tls_factory(
            "example.com:443",
            Duration::from_secs(2),
            Some(&proxy),
            |_| Ok(()),
            || Ok(()),
            || {
                let mut roots = RootCertStore::empty();
                if trusted {
                    roots.add(der).unwrap();
                }
                Ok(ClientConfig::builder()
                    .with_root_certificates(roots)
                    .with_no_client_auth())
            },
        );
        let connected = result.is_ok();
        if let Ok(mut connection) = result {
            let mut payload = [0_u8; 4];
            connection.stream.read_exact(&mut payload).unwrap();
            assert_eq!(&payload, b"ping");
            connection.send(b"pong").unwrap();
            connection.stream.flush().unwrap();
        }
        (connected, server.join().unwrap())
    }

    #[test]
    fn https_proxy_trusted_certificate_tunnels_bidirectionally() {
        assert_eq!(exercise_https_certificate("127.0.0.1", true), (true, true));
    }

    #[test]
    fn https_proxy_untrusted_certificate_never_sends_connect() {
        assert_eq!(
            exercise_https_certificate("127.0.0.1", false),
            (false, false)
        );
    }

    #[test]
    fn https_proxy_hostname_mismatch_never_sends_connect() {
        assert_eq!(
            exercise_https_certificate("wrong.example", true),
            (false, false)
        );
    }

    #[test]
    fn http_connect_rejects_unterminated_oversized_headers() {
        use std::net::TcpListener;
        use std::thread;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut request = Vec::new();
            let mut byte = [0_u8; 1];
            while !request.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            stream
                .write_all(&vec![b'X'; MAX_PROXY_HEADERS + 1])
                .unwrap();
        });
        let config = ProxyConfig {
            kind: ProxyKind::Http,
            host: "127.0.0.1".into(),
            port: address.port(),
            username: None,
            password: None,
            secret: None,
        };
        let result =
            TcpConnection::connect("example.com:443", Duration::from_secs(1), Some(&config));
        assert!(
            matches!(result, Err(ProxyError::Handshake(message)) if message.contains("too large"))
        );
        server.join().unwrap();
    }
