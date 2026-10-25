import unittest

from stage_client_lib import parse


class ParseTest(unittest.TestCase):
    def test_the_client_builds_exact_bytes(self):
        self.assertEqual(('1.6.0', 10600), parse('release=1.6.0\nbuild=10600\n'))

    def test_crlf_blank_and_unknown_lines(self):
        self.assertEqual(('1.6.0-rc1', 10600), parse('\r\nrelease=1.6.0-rc1\r\nextra=x\r\nbuild = 10600 \r\n'))

    def test_a_missing_or_bad_build_is_none(self):
        self.assertEqual(('1.6.0', None), parse('release=1.6.0\n'))
        self.assertEqual(('1.6.0', None), parse('release=1.6.0\nbuild=-1\n'))
        self.assertEqual(('1.6.0', None), parse('release=1.6.0\nbuild=10600x\n'))


if __name__ == '__main__':
    unittest.main()
