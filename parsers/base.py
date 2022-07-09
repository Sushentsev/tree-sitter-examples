from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Tuple, List


@dataclass
class Node:
    class_name: str
    node_type: str
    node_name: str
    points: Tuple[int, int]
    bytes: Tuple[int, int]


class ASTParser(ABC):
    @abstractmethod
    def parse(self, source_code: bytes) -> List[Node]:
        raise NotImplementedError
