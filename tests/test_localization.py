import json
import pathlib
import re
import sys
import unittest
import tempfile
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import localize


class LocalizationTests(unittest.TestCase):
    def strings(self, source):
        data = source.encode()
        tree = localize.PARSER.parse(data)
        return [(localize.template(node, data), localize.eligible(node, data))
                for node in localize.walk(tree.root_node) if node.type == 'string_literal']

    def test_ui_does_not_include_protocol_ids_or_urls_in_click_handlers(self):
        result = self.strings('fun View() { Text("Settings", modifier = Modifier.clickable { openUrl("https://example.com") }); navigate("settings") }')
        self.assertEqual([v[0][0] for v in result if v[1]], ['Settings'])

    def test_string_search_inside_text_is_not_localized(self):
        result = self.strings('fun View(){ Text(if (error.contains("failed")) "Try again" else "Done") }')
        self.assertEqual([v[0][0] for v in result if v[1]], ['Try again', 'Done'])

    def test_dynamic_values_are_kept_as_arguments(self):
        result = self.strings('fun View(){ Text("Hello $name: ${device.name}") }')
        self.assertEqual(result[0][0], ('Hello %1$s: %2$s', ['name', 'device.name']))

    def test_nested_quoted_expression_is_not_translated_separately(self):
        result = self.strings('fun View(){ Text("Result: ${map["key"]}") }')
        self.assertEqual(result[0][0], ('Result: %1$s', ['map["key"]']))
        self.assertFalse(result[1][1])

    def test_animation_labels_are_not_ui(self):
        self.assertFalse(self.strings('fun View(){ Crossfade(state, label = "onboarding_step") {} }')[0][1])

    def test_rich_text_only_localizes_annotated_ui_builders(self):
        result = self.strings('fun View(){ val help = buildAnnotatedString { append("Tap "); withStyle(style) { append("Allow") } }; val protocol = StringBuilder().append("route_id") }')
        self.assertEqual([v[0][0] for v in result if v[1]], ['Tap ', 'Allow'])

    def test_user_names_and_variable_text_remain_unchanged(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = pathlib.Path(temporary)
            path = source / 'pebble/src/commonMain/kotlin/example/ui/View.kt'
            path.parent.mkdir(parents=True)
            path.write_text('package example\nimport org.jetbrains.compose.resources.stringResource\nfun View(title: String){ Text(title); Text(app.title); Text("Settings") }', encoding='utf-8')
            catalog = source / 'catalog.json'
            catalog.write_text(json.dumps({'Settings': ['设置', '設定']}), encoding='utf-8')
            command = [sys.executable, str(ROOT / 'tools/localize.py'), str(source), '--catalog', str(catalog)]
            subprocess.run(command, check=True, capture_output=True)
            result = path.read_text(encoding='utf-8')
            self.assertIn('Text(title)', result)
            self.assertIn('Text(app.title)', result)
            self.assertIn('Text(appText("Settings"))', result)
            self.assertIn('import coredevices.util.localization.appStringResource as stringResource', result)
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual(result, path.read_text(encoding='utf-8'))

    def test_placeholders_match_in_both_languages(self):
        catalog = json.loads((ROOT / 'localization/catalog.json').read_text(encoding='utf-8'))
        self.assertGreater(len(catalog), 1000)
        for english, translations in catalog.items():
            expected = sorted(re.findall(r'%\d+\$s', english))
            for value in translations:
                self.assertTrue(value)
                self.assertEqual(sorted(re.findall(r'%\d+\$s', value)), expected, english)


if __name__ == '__main__':
    unittest.main()
