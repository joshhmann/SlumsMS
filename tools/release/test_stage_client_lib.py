import unittest

from stage_client_lib import data_spec, parse


class ParseTest(unittest.TestCase):
    def test_the_client_builds_exact_bytes(self):
        self.assertEqual(('1.6.0', 10600), parse('release=1.6.0\nbuild=10600\n'))

    def test_crlf_blank_and_unknown_lines(self):
        self.assertEqual(('1.6.0-rc1', 10600), parse('\r\nrelease=1.6.0-rc1\r\nextra=x\r\nbuild = 10600 \r\n'))

    def test_a_missing_or_bad_build_is_none(self):
        self.assertEqual(('1.6.0', None), parse('release=1.6.0\n'))
        self.assertEqual(('1.6.0', None), parse('release=1.6.0\nbuild=-1\n'))
        self.assertEqual(('1.6.0', None), parse('release=1.6.0\nbuild=10600x\n'))


class DataSpecTest(unittest.TestCase):
    def test_a_build_desktop_file_and_its_client_path(self):
        self.assertEqual(('Z:\\MSv83\\SlumsMS-dev\\client-1.6.0\\data\\login.img', 'Data/Map/Obj/login.img'),
                         data_spec('Z:\\MSv83\\SlumsMS-dev\\client-1.6.0\\data\\login.img=Data/Map/Obj/login.img'))
        self.assertEqual(('Z:\\a b\\x.img', 'Data/Map/Map/Map9/910000000.img'), data_spec('Z:\\a b\\x.img=Data/Map/Map/Map9/910000000.img'))

    def test_only_img_files_under_data_with_plain_names(self):
        for bad in ['Z:\\x.img=slums.dll', 'Z:\\x.img=Data/../slums.dll', 'Z:\\x.img=Data/UI/../../a.img', 'Z:\\x.img=/Data/UI/a.img',
                    'Z:\\x.img=Data/UI/a b.img', 'Z:\\x.img=Data/UI/a.img;rm', 'Z:\\x.img=Data/a.wz', 'Z:\\x.img=Data/.img',
                    'x.img=Data/UI/a.img', "Z:\\it's.img=Data/UI/a.img", 'Z:\\x.img', '=Data/UI/a.img', 'Z:\\x.img=Data/UI/a.img\n']:
            self.assertIsNone(data_spec(bad), bad)


if __name__ == '__main__':
    unittest.main()
