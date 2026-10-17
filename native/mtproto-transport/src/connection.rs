use crate::ProxyError;

pub trait Connection {
    fn send(&mut self, packet: &[u8]) -> Result<(), ProxyError>;
    fn receive(&mut self, output: &mut [u8]) -> Result<usize, ProxyError>;
    fn close(&mut self) -> Result<(), ProxyError>;
}
