from pathlib import Path
from typing import List

from tree_sitter import Language, Parser

from parsers.base import ASTParser, Node


def load_file(path: Path) -> bytes:
    with open(path, "rb") as file:
        return file.read()


class KotlinParser(ASTParser):
    def __init__(self, lang_path: str):
        self.parser = Parser()
        self.parser.set_language(Language(lang_path, "kotlin"))
        self.node_kinds = {"function_declaration"}

    def parse_node(self, class_name: str, node) -> Node:
        name = [child for child in node.children if child.type == "simple_identifier"][0].text.decode("utf-8")
        return Node(
            class_name,
            node.type,
            name,
            (node.start_point[0], node.end_point[0]),
            (node.start_byte, node.end_byte)
        )

    def parse_class_node(self, node) -> List[Node]:
        class_name = [child for child in node.children if child.type == "type_identifier"][0].text.decode("utf-8")
        class_body_node = [child for child in node.children if child.type == "class_body"][0]
        tree_nodes = [node for node in class_body_node.children if node.type in self.node_kinds]

        nodes = [self.parse_node(class_name, node) for node in tree_nodes]
        return nodes

    def parse(self, source_code: bytes) -> List[Node]:

        tree = self.parser.parse(source_code)
        root_node = tree.root_node

        class_nodes = [node for node in root_node.children if node.type == "class_declaration"]

        nodes = []
        for class_node in class_nodes:
            nodes.extend(self.parse_class_node(class_node))

        return nodes


if __name__ == "__main__":
    _source_code = load_file(Path("../examples/kotlin/IDELightClassGenerationSupport.kt"))
    _parser = KotlinParser("../build/my-languages.so")
    _nodes = _parser.parse(_source_code)

    for _node in _nodes:
        print(_node)
