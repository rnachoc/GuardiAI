"""Tests for the authorship check, on small files written for each case.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import importlib.util
import tempfile
import textwrap
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "check_authorship", Path(__file__).resolve().parent.parent / "scripts" / "check_authorship.py"
)
check_authorship = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check_authorship)

AUTHOR = check_authorship.AUTHOR


def write(directory: Path, name: str, text: str) -> Path:
    """Writes a dedented file into a directory and returns its path.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """
    path = directory / name
    path.write_text(textwrap.dedent(text).replace("{AUTHOR}", AUTHOR), encoding="utf-8")
    return path


class JavaTest(unittest.TestCase):
    """The check finds Java types, constructors and methods without an author.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """

    def setUp(self):
        """Creates a scratch directory for the test's files.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        self.directory = Path(tempfile.mkdtemp())

    def test_a_fully_documented_class_passes(self):
        """A class whose type, constructor and methods all carry the author passes.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        path = write(self.directory, "Shop.java", """
            package x;

            /**
             * A shop.
             *
             * @author {AUTHOR}
             */
            @Service
            public class Shop {

                private final int size = 3;

                /**
                 * Builds a shop.
                 *
                 * @author {AUTHOR}
                 */
                public Shop(Repository repository) {
                    this.repository = repository;
                }

                /**
                 * Sells.
                 *
                 * @author {AUTHOR}
                 */
                @Transactional
                public Order sell(String sku, int quantity) throws IOException {
                    if (quantity > 0) {
                        return repository.save(new Order(sku));
                    }
                    for (int i = 0; i < 3; i++) {
                        helper(i);
                    }
                    return null;
                }

                /**
                 * A record inside the class.
                 *
                 * @author {AUTHOR}
                 */
                record Line(String sku, int quantity) {
                }
            }
            """)
        self.assertEqual(check_authorship.check_java(path), [])

    def test_a_method_without_the_author_is_reported(self):
        """A method with a Javadoc that lacks the author is reported, with its line.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        path = write(self.directory, "Shop.java", """
            /**
             * @author {AUTHOR}
             */
            public class Shop {

                /** Sells, but says nothing about who wrote it. */
                public Order sell(String sku) {
                    return null;
                }

                List<String> names() {
                    return List.of();
                }
            }
            """)
        problems = check_authorship.check_java(path)
        self.assertEqual(len(problems), 2, problems)
        self.assertIn("Shop.java:8:", problems[0])
        self.assertIn("Shop.java:12:", problems[1])

    def test_a_type_and_an_interface_method_without_the_author_are_reported(self):
        """An undocumented interface and its abstract method are both reported.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        path = write(self.directory, "Gateway.java", """
            public interface Gateway {
                Response send(Request request);
            }
            """)
        self.assertEqual(len(check_authorship.check_java(path)), 2)


class TypeScriptTest(unittest.TestCase):
    """The check finds TypeScript functions, classes and methods without an author.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """

    def setUp(self):
        """Creates a scratch directory for the test's files.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        self.directory = Path(tempfile.mkdtemp())

    def test_documented_declarations_pass_and_missing_ones_are_reported(self):
        """Documented declarations pass; an undocumented function, arrow and method are reported.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        path = write(self.directory, "tools.ts", """
            /**
             * Adds.
             * @author {AUTHOR}
             */
            export function add(a: number, b: number): number {
                return a + b;
            }

            /**
             * A class.
             * @author {AUTHOR}
             */
            export class Box {
                /**
                 * Opens.
                 * @author {AUTHOR}
                 */
                async open(force: boolean): Promise<void> {
                    if (force) {
                        return;
                    }
                }

                close(): void {
                }
            }

            export const twice = (n: number): number => n * 2;

            export async function load() {
            }
            """)
        problems = check_authorship.check_typescript(path)
        self.assertEqual(len(problems), 3, problems)


class PythonAndShellTest(unittest.TestCase):
    """The check finds Python functions and shell functions without an author.

    Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
    """

    def setUp(self):
        """Creates a scratch directory for the test's files.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        self.directory = Path(tempfile.mkdtemp())

    def test_python_function_without_the_author_is_reported(self):
        """A function whose docstring lacks the author is reported; the documented one is not.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        path = write(self.directory, "tool.py", '''
            """A tool.

            Author: {AUTHOR}
            """


            def documented():
                """Does it.

                Author: {AUTHOR}
                """


            def undocumented():
                """Does it too."""
            ''')
        problems = check_authorship.check_python(path)
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("undocumented", problems[0])

    def test_shell_function_without_the_author_is_reported(self):
        """A shell function with no author comment above it is reported.

        Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
        """
        path = write(self.directory, "run.sh", """
            #!/bin/sh
            # Runs things.
            # Author: {AUTHOR}

            # Says hello.
            # Author: {AUTHOR}
            hello() { echo hello; }

            bye() { echo bye; }
            """)
        problems = check_authorship.check_shell(path)
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("run.sh:10:", problems[0])


if __name__ == "__main__":
    unittest.main()
