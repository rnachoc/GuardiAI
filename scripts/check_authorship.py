"""Fails when a class, method or function is missing its author.

The project's rule is that every class, method and function carries a comment naming its
author. This script enforces it for Python, Java, TypeScript and shell, so a missing tag fails
`make lint` instead of depending on a reviewer. Standard library only.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import ast
import re
import sys
from pathlib import Path

AUTHOR = "Ignacio Rodriguez Caro <rnachoccontact@gmail.com>"
SKIPPED_DIRS = {".git", "node_modules", "target", "dist", "coverage", ".cache", ".venv", "__pycache__"}

JAVA_TYPE = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(?:class|interface|enum|record|@interface)\s+\w+")
JAVA_METHOD = re.compile(
    r"^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|default|native)\s+)*"
    r"(?:<[^>]+>\s+)?[\w.<>\[\]?, ]+?\s+(\w+)\s*\([^;{]*(?:\)\s*(?:throws [\w., ]+)?\s*[{;]?)?\s*$"
)
JAVA_CONSTRUCTOR = re.compile(r"^\s*(?:public|protected|private)\s+([A-Z]\w*)\s*\(")
JAVA_NOT_A_METHOD = {"if", "for", "while", "switch", "catch", "return", "new", "throw", "else", "try", "do", "synchronized"}
TS_DECLARATION = re.compile(
    r"^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function\s*\*?\s*(\w+)|class\s+(\w+)|"
    r"(?:const|let)\s+(\w+)\s*(?::[^=]+)?=\s*(?:async\s+)?(?:\([^)]*\)|\w+)\s*(?::\s*[^=]+)?=>)"
)
TS_METHOD = re.compile(
    r"^\s+(?:(?:public|private|protected|static|readonly|async|override)\s+)*(?!if\b|for\b|while\b|switch\b|catch\b|return\b)"
    r"(\w+)\s*(?:<[^>]*>)?\([^)]*\)\s*(?::\s*[^{]+)?\{\s*$"
)
SHELL_FUNCTION = re.compile(r"^\s*(\w+)\s*\(\)\s*\{")


def files(root: Path, suffixes: tuple[str, ...]) -> list[Path]:
    """Lists the project's files with the given suffixes, skipping build output and dependencies.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    found = []
    for path in root.rglob("*"):
        if path.is_file() and path.suffix in suffixes and not SKIPPED_DIRS.intersection(path.relative_to(root).parts):
            found.append(path)
    return sorted(found)


def check_python(path: Path) -> list[str]:
    """Requires the author in the docstring of the module, each class and each function.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    problems = []
    if AUTHOR not in (ast.get_docstring(tree) or ""):
        problems.append(f"{path}:1: module docstring has no author")
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            if AUTHOR not in (ast.get_docstring(node) or ""):
                problems.append(f"{path}:{node.lineno}: {node.name} has no author in its docstring")
    return problems


def preceding_comment(lines: list[str], index: int, opener: str, closer: str) -> str:
    """Returns the block comment that ends right above a line, skipping annotations and blank lines.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    cursor = index - 1
    while cursor >= 0 and (not lines[cursor].strip() or lines[cursor].strip().startswith("@")):
        cursor -= 1
    if cursor < 0 or not lines[cursor].strip().endswith(closer):
        return ""
    end = cursor
    while cursor >= 0 and opener not in lines[cursor]:
        cursor -= 1
    return "\n".join(lines[max(cursor, 0) : end + 1])


def check_java(path: Path) -> list[str]:
    """Requires a Javadoc with the author above each type, constructor and method.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    problems = []
    depth = 0
    for index, line in enumerate(lines):
        stripped = line.strip()
        code = re.sub(r'"(?:\\.|[^"\\])*"', '""', line.split("//")[0])
        is_declaration = False
        if not stripped.startswith(("*", "/*", "//", "@")):
            if JAVA_TYPE.match(code):
                is_declaration = True
            elif depth >= 1:
                method = JAVA_METHOD.match(code)
                constructor = JAVA_CONSTRUCTOR.match(code)
                if constructor or (method and method.group(1) not in JAVA_NOT_A_METHOD and "=" not in code.split("(")[0]):
                    is_declaration = depth == 1 or bool(re.match(r"^\s*(public|protected|private|static)\b", code))
        if is_declaration and AUTHOR not in preceding_comment(lines, index, "/**", "*/"):
            problems.append(f"{path}:{index + 1}: declaration has no Javadoc with the author: {stripped[:70]}")
        depth += code.count("{") - code.count("}")
    return problems


def check_typescript(path: Path) -> list[str]:
    """Requires a JSDoc with the author above each function, class, arrow function and method.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    problems = []
    for index, line in enumerate(lines):
        if line.strip().startswith(("*", "/*", "//")):
            continue
        if TS_DECLARATION.match(line) or TS_METHOD.match(line):
            if AUTHOR not in preceding_comment(lines, index, "/**", "*/"):
                problems.append(f"{path}:{index + 1}: declaration has no JSDoc with the author: {line.strip()[:70]}")
    return problems


def check_shell(path: Path) -> list[str]:
    """Requires the author in the file header and in a comment above each shell function.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    problems = []
    if AUTHOR not in "\n".join(lines[:15]):
        problems.append(f"{path}:1: header has no author")
    for index, line in enumerate(lines):
        if SHELL_FUNCTION.match(line):
            above = []
            cursor = index - 1
            while cursor >= 0 and lines[cursor].lstrip().startswith("#"):
                above.append(lines[cursor])
                cursor -= 1
            if AUTHOR not in "\n".join(above):
                problems.append(f"{path}:{index + 1}: function has no author comment above it")
    return problems


def main() -> int:
    """Checks every source file and prints each declaration that is missing its author.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    problems: list[str] = []
    for path in files(root, (".py",)):
        problems += check_python(path)
    for path in files(root, (".java",)):
        problems += check_java(path)
    for path in files(root, (".ts", ".tsx")):
        if not path.name.endswith(".d.ts"):
            problems += check_typescript(path)
    for path in files(root, (".sh",)) + [root / ".githooks" / "pre-commit"]:
        if path.exists():
            problems += check_shell(path)
    for problem in problems:
        print(problem)
    print(f"authorship: {len(problems)} declaration(s) without the author")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
