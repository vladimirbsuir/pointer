use serde::Serialize;
use tree_sitter::{Language, Node, Parser};

pub const MAX_CHUNK_CHARS: usize = 2_000;
const MAX_WINDOW_LINES: usize = 60;
const MAX_CARRY_CHARS: usize = 400;
const MIN_CHUNK_SIGNIFICANT_CHARS: usize = 16;
const MAX_SYMBOL_CHARS: usize = 200;

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Chunk {
    pub symbol: Option<String>,
    pub kind: String,
    pub start_line: usize,
    pub end_line: usize,
    pub content: String,
}

pub fn chunk(path: &str, source: &str) -> Vec<Chunk> {
    let chunks = language_for(path)
        .and_then(|language| syntax_chunks(source, &language))
        .unwrap_or_else(|| line_windows(source, 0, source.len(), 1, None));
    chunks
        .into_iter()
        .filter(|chunk| significant_chars(&chunk.content) >= MIN_CHUNK_SIGNIFICANT_CHARS)
        .collect()
}

fn language_for(path: &str) -> Option<Language> {
    let extension = path.rsplit_once('.')?.1.to_ascii_lowercase();
    let language = match extension.as_str() {
        "rs" => tree_sitter_rust::LANGUAGE,
        "ts" | "mts" | "cts" => tree_sitter_typescript::LANGUAGE_TYPESCRIPT,
        "tsx" => tree_sitter_typescript::LANGUAGE_TSX,
        "js" | "jsx" | "mjs" | "cjs" => tree_sitter_javascript::LANGUAGE,
        "py" => tree_sitter_python::LANGUAGE,
        "java" => tree_sitter_java::LANGUAGE,
        "go" => tree_sitter_go::LANGUAGE,
        _ => return None,
    };
    Some(language.into())
}

fn syntax_chunks(source: &str, language: &Language) -> Option<Vec<Chunk>> {
    let mut parser = Parser::new();
    parser.set_language(language).ok()?;
    let tree = parser.parse(source, None)?;
    let mut chunks = Vec::new();
    split_children(tree.root_node(), source, &[], &mut chunks, None);
    Some(chunks)
}

#[derive(Clone, Copy)]
struct Carry {
    start_byte: usize,
    start_line: usize,
}

struct Group<'tree> {
    carry: Option<Carry>,
    nodes: Vec<Node<'tree>>,
}

impl<'tree> Group<'tree> {
    fn start(&self) -> Option<Carry> {
        self.carry.or_else(|| {
            self.nodes.first().map(|first| Carry {
                start_byte: first.start_byte(),
                start_line: first.start_position().row + 1,
            })
        })
    }

    fn len_with(&self, node: &Node) -> usize {
        match self.start() {
            Some(start) => node.end_byte() - start.start_byte,
            None => node_len(node),
        }
    }

    fn len(&self) -> usize {
        match (self.start(), self.nodes.last()) {
            (Some(start), Some(last)) => last.end_byte() - start.start_byte,
            _ => 0,
        }
    }
}

fn split_children(
    node: Node,
    source: &str,
    scope: &[String],
    chunks: &mut Vec<Chunk>,
    carry: Option<Carry>,
) {
    let mut cursor = node.walk();
    let mut group = Group {
        carry,
        nodes: Vec::new(),
    };
    for child in node.children(&mut cursor) {
        if node_len(&child) > MAX_CHUNK_CHARS {
            let header = if is_header(&group, source) {
                let start = group.start();
                group = Group {
                    carry: None,
                    nodes: Vec::new(),
                };
                start
            } else {
                flush(&mut group, source, scope, chunks);
                None
            };
            split_oversized(child, source, scope, chunks, header);
        } else {
            if group.len_with(&child) > MAX_CHUNK_CHARS {
                if group.nodes.is_empty() {
                    flush_header(&mut group, child.start_byte(), source, scope, chunks);
                } else {
                    flush(&mut group, source, scope, chunks);
                }
            }
            group.nodes.push(child);
        }
    }
    flush(&mut group, source, scope, chunks);
}

fn is_header(group: &Group, source: &str) -> bool {
    group.len() <= MAX_CARRY_CHARS
        && group
            .nodes
            .iter()
            .all(|node| name_of(*node, source).is_none())
}

fn split_oversized(
    node: Node,
    source: &str,
    scope: &[String],
    chunks: &mut Vec<Chunk>,
    header: Option<Carry>,
) {
    if node.child_count() == 0 {
        let start = header.unwrap_or(Carry {
            start_byte: node.start_byte(),
            start_line: node.start_position().row + 1,
        });
        let symbol = scope_symbol(scope, &[]);
        chunks.extend(line_windows(
            source,
            start.start_byte,
            node.end_byte(),
            start.start_line,
            symbol,
        ));
        return;
    }
    let mut inner_scope = scope.to_vec();
    if let Some(name) = name_of(node, source) {
        inner_scope.push(name);
    }
    split_children(node, source, &inner_scope, chunks, header);
}

fn flush_header(
    group: &mut Group,
    end_byte: usize,
    source: &str,
    scope: &[String],
    chunks: &mut Vec<Chunk>,
) {
    let Some(header) = group.carry.take() else {
        return;
    };
    let content = source[header.start_byte..end_byte].trim_end();
    chunks.push(Chunk {
        symbol: scope_symbol(scope, &[]),
        kind: "header".to_string(),
        start_line: header.start_line,
        end_line: header.start_line + content.lines().count().saturating_sub(1),
        content: content.to_string(),
    });
}

fn flush(group: &mut Group, source: &str, scope: &[String], chunks: &mut Vec<Chunk>) {
    let mut nodes = std::mem::take(&mut group.nodes);
    let group_end = nodes.last().map(Node::end_byte);
    while nodes.last().is_some_and(|node| !node.is_named()) {
        nodes.pop();
    }
    if group.carry.is_none() {
        let leading_tokens = nodes.iter().take_while(|node| !node.is_named()).count();
        nodes.drain(..leading_tokens);
    }
    let (Some(first), Some(last)) = (nodes.first(), nodes.last()) else {
        if let Some(end_byte) = group_end {
            flush_header(group, end_byte, source, scope, chunks);
        }
        return;
    };
    let start = group.carry.take().unwrap_or(Carry {
        start_byte: first.start_byte(),
        start_line: first.start_position().row + 1,
    });
    let definitions: Vec<&Node> = nodes
        .iter()
        .filter(|node| node.is_named() && !node.kind().contains("comment"))
        .collect();
    let names: Vec<String> = definitions
        .iter()
        .filter_map(|node| name_of(**node, source))
        .collect();
    let kind = match definitions.as_slice() {
        [single] => single.kind().to_string(),
        _ => "group".to_string(),
    };
    chunks.push(Chunk {
        symbol: scope_symbol(scope, &names),
        kind,
        start_line: start.start_line,
        end_line: end_line(last),
        content: source[start.start_byte..last.end_byte()].to_string(),
    });
}

fn name_of(node: Node, source: &str) -> Option<String> {
    if let Some(name) = node.child_by_field_name("name") {
        return identifier(name, source);
    }
    for field in ["declaration", "definition"] {
        if let Some(inner) = node.child_by_field_name(field) {
            return name_of(inner, source);
        }
    }
    let mut cursor = node.walk();
    let declarator = node
        .named_children(&mut cursor)
        .find(|child| child.kind().ends_with("declarator") || child.kind().ends_with("_spec"));
    if let Some(declarator) = declarator {
        return name_of(declarator, source);
    }
    node.kind()
        .starts_with("impl")
        .then(|| node.child_by_field_name("type"))
        .flatten()
        .and_then(|name| identifier(name, source))
}

fn identifier(node: Node, source: &str) -> Option<String> {
    if node.named_child_count() == 0 {
        return text(node, source);
    }
    ["name", "type"]
        .into_iter()
        .find_map(|field| node.child_by_field_name(field))
        .and_then(|inner| identifier(inner, source))
}

fn text(node: Node, source: &str) -> Option<String> {
    let value = source.get(node.start_byte()..node.end_byte())?.trim();
    (!value.is_empty() && !value.contains('\n')).then(|| value.to_string())
}

fn scope_symbol(scope: &[String], names: &[String]) -> Option<String> {
    let prefix = scope.join(".");
    let symbol = match (prefix.is_empty(), names.is_empty()) {
        (true, true) => return None,
        (false, true) => prefix,
        (true, false) => names.join(", "),
        (false, false) => names
            .iter()
            .map(|name| format!("{prefix}.{name}"))
            .collect::<Vec<_>>()
            .join(", "),
    };
    Some(truncate(&symbol, MAX_SYMBOL_CHARS))
}

fn line_windows(
    source: &str,
    start: usize,
    end: usize,
    first_line: usize,
    symbol: Option<String>,
) -> Vec<Chunk> {
    let mut chunks = Vec::new();
    let mut current = String::new();
    let mut current_start = first_line;
    let mut line_number = first_line;
    let mut lines_in_window = 0;
    for line in source[start..end].split_inclusive('\n') {
        if lines_in_window > 0
            && (current.len() + line.len() > MAX_CHUNK_CHARS || lines_in_window == MAX_WINDOW_LINES)
        {
            chunks.push(window(&symbol, current_start, line_number - 1, &current));
            current.clear();
            current_start = line_number;
            lines_in_window = 0;
        }
        current.push_str(line);
        lines_in_window += 1;
        line_number += 1;
    }
    if lines_in_window > 0 {
        chunks.push(window(&symbol, current_start, line_number - 1, &current));
    }
    chunks
}

fn window(symbol: &Option<String>, start_line: usize, end_line: usize, content: &str) -> Chunk {
    Chunk {
        symbol: symbol.clone(),
        kind: "lines".to_string(),
        start_line,
        end_line,
        content: truncate(content, MAX_CHUNK_CHARS),
    }
}

fn end_line(node: &Node) -> usize {
    let end = node.end_position();
    if end.column == 0 && end.row > node.start_position().row {
        end.row
    } else {
        end.row + 1
    }
}

fn node_len(node: &Node) -> usize {
    node.end_byte() - node.start_byte()
}

fn significant_chars(content: &str) -> usize {
    content.chars().filter(|char| !char.is_whitespace()).count()
}

fn truncate(value: &str, max_bytes: usize) -> String {
    if value.len() <= max_bytes {
        return value.to_string();
    }
    let mut end = max_bytes;
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rust_items_become_separate_chunks_with_symbols() {
        let body = "    let value = 1;\n".repeat(60);
        let source = format!(
            "use std::fmt;\n\npub struct Service {{\n    name: String,\n}}\n\nimpl Service {{\n    pub fn create(&self) {{\n{body}    }}\n\n    pub fn delete(&self) {{\n{body}    }}\n}}\n"
        );
        let chunks = chunk("src/service.rs", &source);
        let create = chunks
            .iter()
            .find(|chunk| chunk.symbol.as_deref() == Some("Service.create"))
            .expect("create chunk");
        assert!(create.content.contains("pub fn create(&self)"));
        assert!(!create.content.contains("pub struct Service"));
        assert!(
            chunks
                .iter()
                .any(|chunk| chunk.content.starts_with("use std::fmt")
                    && chunk.content.contains("pub struct Service"))
        );
        assert!(
            chunks
                .iter()
                .any(|chunk| chunk.symbol.as_deref() == Some("Service.delete"))
        );
        assert!(
            chunks
                .iter()
                .all(|chunk| chunk.content.len() <= MAX_CHUNK_CHARS)
        );
    }

    #[test]
    fn signature_of_a_large_function_stays_with_its_body() {
        let body = "    let value = compute(1);
"
        .repeat(90);
        let source = format!(
            "pub fn large(input: u32) -> u32 {{
{body}    input
}}
"
        );
        let chunks = chunk("src/large.rs", &source);
        assert!(chunks.len() > 1);
        assert!(chunks[0].content.starts_with("pub fn large(input: u32)"));
        assert_eq!(chunks[0].start_line, 1);
        assert!(
            chunks
                .iter()
                .all(|chunk| chunk.symbol.as_deref() == Some("large"))
        );
        assert!(
            chunks
                .iter()
                .all(|chunk| chunk.content.len() <= MAX_CHUNK_CHARS)
        );
    }

    #[test]
    fn header_that_does_not_fit_with_the_first_child_becomes_its_own_chunk() {
        let literal = "x".repeat(1_970);
        let rest = "    total = total + 1\n".repeat(30);
        let source = format!("def build_payload(total):\n    payload = \"{literal}\"\n{rest}");
        let chunks = chunk("payload.py", &source);
        assert!(
            chunks
                .iter()
                .all(|chunk| chunk.content.len() <= MAX_CHUNK_CHARS)
        );
        assert_eq!(chunks[0].kind, "header");
        assert!(chunks[0].content.starts_with("def build_payload(total):"));
    }

    #[test]
    fn carried_header_is_flushed_before_a_child_that_fills_a_chunk() {
        let rows = "      <li className=\"row\">item</li>\n".repeat(52);
        let source =
            format!("export function List() {{\n  return (\n    <ul>\n{rows}    </ul>\n  );\n}}\n");
        let chunks = chunk("src/List.tsx", &source);
        assert!(
            chunks
                .iter()
                .all(|chunk| chunk.content.len() <= MAX_CHUNK_CHARS)
        );
    }

    #[test]
    fn small_siblings_are_merged() {
        let source = "export function first() {\n  return 1;\n}\n\nexport function second() {\n  return 2;\n}\n";
        let chunks = chunk("src/util.ts", source);
        assert_eq!(chunks.len(), 1);
        assert_eq!(chunks[0].symbol.as_deref(), Some("first, second"));
        assert_eq!(chunks[0].start_line, 1);
        assert_eq!(chunks[0].end_line, 7);
    }

    #[test]
    fn python_methods_carry_class_scope() {
        let body = "        total = total + 1\n".repeat(90);
        let source = format!(
            "class Counter:\n    def increment(self):\n{body}\n    def reset(self):\n{body}"
        );
        let chunks = chunk("counter.py", &source);
        assert!(
            chunks
                .iter()
                .any(|chunk| chunk.symbol.as_deref() == Some("Counter.increment"))
        );
    }

    #[test]
    fn unknown_languages_use_line_windows() {
        let source = "line of markdown text\n".repeat(150);
        let chunks = chunk("docs/readme.md", &source);
        assert!(chunks.len() >= 2);
        assert!(chunks.iter().all(|chunk| chunk.kind == "lines"));
        assert_eq!(chunks[0].start_line, 1);
        assert_eq!(chunks[1].start_line, chunks[0].end_line + 1);
    }

    #[test]
    fn oversized_leaf_is_split_into_windows() {
        let literal = "x".repeat(80);
        let lines = format!("    \"{literal}\",\n").repeat(60);
        let source = format!("const DATA: &[&str] = &[\n{lines}];\n");
        let chunks = chunk("data.rs", &source);
        assert!(chunks.len() > 1);
        assert!(
            chunks
                .iter()
                .all(|chunk| chunk.content.len() <= MAX_CHUNK_CHARS)
        );
    }
}
