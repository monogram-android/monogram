use crate::MarkupBlockDto;

pub(super) fn html_to_markdown(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut rest = raw;
    let mut link_url: Option<String> = None;
    while let Some(open) = rest.find('<') {
        out.push_str(&decode_html(&rest[..open]));
        let Some(close) = rest[open..].find('>') else {
            out.push_str(&decode_html(&rest[open..]));
            return out;
        };
        let original_tag = rest[open + 1..open + close].trim();
        let tag = original_tag.to_ascii_lowercase();
        let replacement = if tag.starts_with("br") {
            "\n"
        } else if tag == "b" || tag == "strong" {
            "**"
        } else if tag == "/b" || tag == "/strong" {
            "**"
        } else if tag == "i" || tag == "em" {
            "*"
        } else if tag == "/i" || tag == "/em" {
            "*"
        } else if tag == "u" {
            "__"
        } else if tag == "/u" {
            "__"
        } else if tag == "s" || tag == "del" || tag == "strike" {
            "~~"
        } else if tag == "/s" || tag == "/del" || tag == "/strike" {
            "~~"
        } else if tag == "code" {
            "`"
        } else if tag == "/code" {
            "`"
        } else if tag == "li" {
            "\n- "
        } else if tag == "/li" {
            "\n"
        } else if tag == "dt" || tag == "dd" {
            "\n"
        } else if tag == "/dt" || tag == "/dd" {
            "\n"
        } else if tag == "summary" {
            "\n"
        } else if tag == "/summary" {
            "\n"
        } else if tag.len() == 2 && tag.starts_with('h') && tag.as_bytes()[1].is_ascii_digit() {
            ""
        } else if tag.len() == 3 && tag.starts_with("/h") && tag.as_bytes()[2].is_ascii_digit() {
            "\n"
        } else if tag.starts_with("p") || tag == "/p" || tag.starts_with("div") || tag == "/div" {
            "\n"
        } else {
            ""
        };
        if tag.len() == 2 && tag.starts_with('h') && tag.as_bytes()[1].is_ascii_digit() {
            out.push_str(&"#".repeat((tag.as_bytes()[1] - b'0') as usize));
            out.push(' ');
        } else if tag.starts_with("a ") || tag.starts_with("a href=") {
            out.push('[');
            link_url = original_tag
                .split("href=")
                .nth(1)
                .and_then(|value| value.trim().trim_matches('/').split_whitespace().next())
                .map(|value| value.trim_matches(['\"', '\'']).to_string());
        } else if tag == "/a" {
            out.push(']');
            out.push('(');
            out.push_str(link_url.take().as_deref().unwrap_or(""));
            out.push(')');
        } else {
            out.push_str(replacement);
        }
        rest = &rest[open + close + 1..];
    }
    out.push_str(&decode_html(rest));
    out
}

fn decode_html(text: &str) -> String {
    text.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
}

pub(super) fn parse_html_table(raw: &str) -> Option<MarkupBlockDto> {
    if !raw.to_ascii_lowercase().contains("<table") {
        return None;
    }
    let mut rows = Vec::new();
    let mut rest = raw;
    while let Some(start) = rest.to_ascii_lowercase().find("<tr") {
        let after = &rest[start..];
        let end = after.to_ascii_lowercase().find("</tr>")?;
        let row = &after[..end];
        let mut cells = Vec::new();
        let mut cell_rest = row;
        loop {
            let lower = cell_rest.to_ascii_lowercase();
            let Some(open) = lower.find("<td").or_else(|| lower.find("<th")) else {
                break;
            };
            let content_start = cell_rest[open..].find('>')? + open + 1;
            let close = cell_rest[content_start..]
                .to_ascii_lowercase()
                .find("</td>")
                .or_else(|| {
                    cell_rest[content_start..]
                        .to_ascii_lowercase()
                        .find("</th>")
                })?;
            cells.push(
                html_to_markdown(&cell_rest[content_start..content_start + close])
                    .trim()
                    .to_string(),
            );
            cell_rest = &cell_rest[content_start + close + 5..];
        }
        if !cells.is_empty() {
            rows.push(cells);
        }
        rest = &after[end + 5..];
    }
    let headers = rows.first()?.clone();
    Some(MarkupBlockDto {
        kind: "table".into(),
        text: String::new(),
        entities: Vec::new(),
        language: None,
        level: 0,
        headers,
        rows: rows.into_iter().skip(1).collect(),
    })
}

pub(super) fn parse_html_details(raw: &str) -> Option<MarkupBlockDto> {
    let lower = raw.to_ascii_lowercase();
    if !lower.contains("<details") || !lower.contains("</details>") {
        return None;
    }
    let summary = lower
        .find("<summary")
        .and_then(|start| {
            let body_start = raw[start..].find('>').map(|offset| start + offset + 1)?;
            let body_end = lower[body_start..].find("</summary>")? + body_start;
            Some(
                html_to_markdown(&raw[body_start..body_end])
                    .trim()
                    .to_owned(),
            )
        })
        .unwrap_or_else(|| "Details".into());
    let content_start = lower
        .find("</summary>")
        .map(|end| end + "</summary>".len())
        .unwrap_or(0);
    let content_end = lower.rfind("</details>").unwrap_or(raw.len());
    let content = if content_start < content_end {
        html_to_markdown(&raw[content_start..content_end])
            .trim()
            .to_owned()
    } else {
        String::new()
    };
    Some(MarkupBlockDto {
        kind: "details".into(),
        text: format!("{summary}\n{content}"),
        entities: Vec::new(),
        language: None,
        level: 0,
        headers: Vec::new(),
        rows: Vec::new(),
    })
}
