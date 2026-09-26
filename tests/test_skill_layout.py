"""Checks that every skill under skills/ is complete and describes itself.

The agent runtimes look up a skill by directory name, and Codex reads
agents/openai.yaml for the interface text. A mismatch there fails silently: the
skill simply never loads, or shows up with no description.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / "skills"


def frontmatter_lines(skill_md):
    """Return the frontmatter lines of a SKILL.md, without the --- fences."""
    lines = skill_md.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].strip() != "---":
        return []
    for index, line in enumerate(lines[1:], start=1):
        if line.strip() == "---":
            return lines[1:index]
    return []


def frontmatter_field(skill_md, field):
    for line in frontmatter_lines(skill_md):
        match = re.match(rf"{field}:\s*(.+?)\s*$", line)
        if match:
            return match.group(1).strip().strip('"')
    return None


class SkillLayoutTest(unittest.TestCase):
    def skill_dirs(self):
        dirs = sorted(path for path in SKILLS.iterdir() if path.is_dir())
        self.assertTrue(dirs, "no skill directories found")
        return dirs

    def test_every_skill_has_a_skill_md_named_after_its_directory(self):
        for skill_dir in self.skill_dirs():
            with self.subTest(skill=skill_dir.name):
                skill_md = skill_dir / "SKILL.md"
                self.assertTrue(skill_md.is_file(), "SKILL.md is missing")
                self.assertEqual(
                    frontmatter_field(skill_md, "name"),
                    skill_dir.name,
                    "frontmatter name must match the directory name",
                )

    def test_every_skill_has_a_description(self):
        for skill_dir in self.skill_dirs():
            with self.subTest(skill=skill_dir.name):
                description = frontmatter_field(skill_dir / "SKILL.md", "description")
                self.assertTrue(description, "description is missing")

    def test_every_skill_has_interface_metadata(self):
        for skill_dir in self.skill_dirs():
            with self.subTest(skill=skill_dir.name):
                openai_yaml = skill_dir / "agents" / "openai.yaml"
                self.assertTrue(openai_yaml.is_file(), "agents/openai.yaml is missing")
                contents = openai_yaml.read_text(encoding="utf-8")
                for field in ("interface:", "display_name:", "short_description:"):
                    self.assertIn(field, contents)


if __name__ == "__main__":
    unittest.main()
