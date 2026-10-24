"""Externalize reviewed UI literals into shared Compose resources at build time."""

import argparse
import hashlib
import json
import pathlib
import re
import xml.etree.ElementTree as ET

import tree_sitter_kotlin
from tree_sitter import Language, Parser

PARSER = Parser(Language(tree_sitter_kotlin.language()))
LABELS = {'title', 'description', 'text', 'label', 'message', 'confirmText',
          'cancelText', 'dismissText', 'contentDescription', 'placeholder',
          'headline', 'supportingText', 'subtitle', 'buttonText', 'hint'}
LABELS |= {'itemText', 'unit', 'question', 'answer'}
CALLS = {'Text', 'SectionText', 'showSnackbar', 'BasicText', 'CoreButton', 'Section',
         'TopLevelType', 'errorDialog', 'setContentTitle', 'setContentText', 'setTitle',
         'setMessage', 'setPositiveButton', 'setNegativeButton', 'Toast.makeText',
         'AnnotatedString'}


def text(node, source):
    return source[node.start_byte:node.end_byte].decode()


def ancestors(node):
    while node.parent:
        node = node.parent
        yield node


def template(node, source):
    raw = text(node, source)
    if raw.startswith('"""'):
        return None
    value = raw[1:-1]
    args = []
    interpolations = {text(c, source): text(c, source)[2:-1]
                      for c in node.children if c.type == 'interpolation'}
    pattern = r'(?<!\\)\$\{(?:[^{}]|\{[^{}]*\})*\}|(?<!\\)\$[A-Za-z_][A-Za-z_0-9]*'

    def substitute(match):
        token = match.group()
        expression = interpolations.get(token, token[1:] if not token.startswith('${') else token[2:-1])
        args.append(expression)
        return f'%{len(args)}$s'

    value = re.sub(pattern, substitute, value)
    try:
        value = json.loads('"' + value.replace('\\$', '$') + '"')
    except ValueError:
        return None
    return value, args


def walk(node):
    cursor = node.walk()
    while True:
        yield cursor.node
        if cursor.goto_first_child():
            continue
        while not cursor.goto_next_sibling():
            if not cursor.goto_parent():
                return


def eligible(node, source):
    parents = list(ancestors(node))
    if any(p.type in ('string_literal', 'annotation', 'import', 'package_header') for p in parents):
        return False
    for parent in parents:
        if parent.type == 'property_declaration' and re.match(r'\s*(?:private\s+)?const\s', text(parent, source)):
            return False
        if parent.type == 'value_argument':
            call = parent.parent.parent if parent.parent else None
            callee = text(call.named_children[0], source) if call and call.type == 'call_expression' else ''
            if callee.startswith('animate') or callee in ('Crossfade', 'AnimatedContent', 'updateTransition'):
                return False
            name = re.match(r'\s*(\w+)\s*=', text(parent, source))
            if name and name[1] in LABELS:
                return True
            if call and call.type == 'call_expression':
                if callee == 'append' and any(
                    p.type == 'call_expression' and text(p.named_children[0], source) == 'buildAnnotatedString'
                    for p in ancestors(call)
                ):
                    return True
                if callee in CALLS or callee.rsplit('.', 1)[-1] in CALLS:
                    return True
            return False
        if parent.type in ('function_declaration', 'class_declaration'):
            break
    return False


def enum_label(node, source):
    entry = next((p for p in ancestors(node) if p.type == 'enum_entry'), None)
    declaration = next((p for p in ancestors(node) if p.type == 'class_declaration'), None)
    if not entry or not declaration:
        return False
    header = text(declaration, source).split('{', 1)[0]
    if not re.search(r'val\s+(?:displayName|englishDisplayName|title|englishTitle)', header):
        return False
    argument = node.parent
    if argument.type != 'value_argument':
        return False
    args = [n for n in argument.parent.named_children if n.type == 'value_argument']
    return args.index(argument) == 1 or 'description =' in text(argument, source)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('--catalog', type=pathlib.Path)
    parser.add_argument('--inventory', type=pathlib.Path)
    args = parser.parse_args()
    found = {}
    files = []
    for module in ('composeApp', 'pebble', 'util', 'experimental', 'libpebble3'):
        for path in (args.source / module / 'src').glob('*/kotlin/**/*.kt'):
            rel = path.relative_to(args.source).as_posix()
            metadata_only = rel.endswith('/WatchPrefEntity.kt')
            if not metadata_only and not any(part in rel for part in ('/ui/', '/theme/', '/mod/')):
                continue
            source = path.read_bytes()
            tree = PARSER.parse(source)
            selected = []
            for node in walk(tree.root_node):
                if node.type != 'string_literal':
                    continue
                result = template(node, source)
                if result is None:
                    continue
                value, expressions = result
                native_label = '/mod/' in rel and re.search('[\u3400-\u9fff]', value) and not any(
                    p.type == 'string_literal' or (p.type == 'call_expression' and
                    text(p.named_children[0], source) == 'appText') for p in ancestors(node))
                if not native_label and not eligible(node, source) and not enum_label(node, source):
                    continue
                if not re.search('[A-Za-z\u3400-\u9fff]', value) or re.fullmatch(r'[%\d\W]+', value):
                    continue
                found.setdefault(value, []).append(f'{rel}:{node.start_point.row + 1}')
                if not metadata_only:
                    selected.append((node, value, expressions))
            if metadata_only:
                continue
            files.append((path, source, selected))
    if args.inventory:
        args.inventory.write_text(json.dumps(found, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if not args.catalog:
        print(f'{len(found)} unique UI strings in {len(files)} files')
        return
    catalog = json.loads(args.catalog.read_text(encoding='utf-8'))
    placeholders = re.compile(r'%\d+\$s')
    for english, translations in catalog.items():
        if any(sorted(placeholders.findall(english)) != sorted(placeholders.findall(v)) for v in translations):
            raise SystemExit(f'Translation placeholder mismatch: {english}')
    missing = sorted(set(found) - set(catalog))
    (args.source / 'localization-new-strings.json').write_text(
        json.dumps({key: found[key] for key in missing}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for path, source, selected in files:
        edits = []
        for node, value, expressions in selected:
            if value not in catalog:
                continue
            if any(p.type == 'enum_entry' for p in ancestors(node)):
                continue
            # Nested literals are skipped by eligible(), so replacements never overlap.
            call = 'appText(' + kotlin(value)
            if expressions:
                call += ', ' + ', '.join(expressions)
            edits.append((node.start_byte, node.end_byte, (call + ')').encode()))
        for node in walk(PARSER.parse(source).root_node):
            if node.type != 'navigation_expression' or not eligible(node, source):
                continue
            expression = node
            value = text(expression, source)
            function = next((p for p in ancestors(node) if p.type == 'function_declaration'), None)
            function_name = text(function.child_by_field_name('name'), source) if function else ''
            pref_label = path.name == 'WatchPrefsScreen.kt' and value in {
                'pref.displayName', 'item.pref.displayName', 'pref.description', 'item.pref.description'}
            enum_option = (path.name == 'WatchPrefsScreen.kt' and function_name == 'enumPref'
                           and value == 'it.displayName')
            settings_option = (path.name == 'WatchSettingsScreen.kt' and function_name == 'rememberSettingsItemsState'
                               and value == 'it.displayName')
            if path.name == 'WatchSettingsScreen.kt' and value == 'it.name':
                call = next((p for p in ancestors(node) if p.type == 'call_expression'), None)
                settings_option = bool(call and 'items = HealthGender.entries' in text(call, source))
            if pref_label or enum_option or settings_option:
                replacement = f'{value}?.let {{ appText(it) }}' if value.endswith('.description') else f'appText({value})'
                edits.append((expression.start_byte, expression.end_byte, replacement.encode()))
        if edits:
            before_errors = sum(n.type == 'ERROR' or n.is_missing for n in walk(PARSER.parse(source).root_node))
            for start, end, replacement in sorted(edits, reverse=True):
                source = source[:start] + replacement + source[end:]
            source = re.sub(rb'(package [^\r\n]+)', rb'\1\n\nimport coredevices.util.localization.appText', source, count=1)
            after_errors = sum(n.type == 'ERROR' or n.is_missing for n in walk(PARSER.parse(source).root_node))
            if after_errors > before_errors:
                raise SystemExit(f'Localization introduced Kotlin syntax errors in {path}')
            path.write_bytes(source)
    generate(args.source, catalog)
    print(f'Localized {len(found) - len(missing)}/{len(found)} source strings; {len(missing)} new strings reported')


def kotlin(value):
    return json.dumps(value, ensure_ascii=False).replace('$', '\\$')


def generate(source, catalog):
    # XML is also emitted so the translation work can be moved upstream independently.
    root = source / 'util/src/commonMain/composeResources'
    for locale, index in (('values', None), ('values-zh', 0), ('values-ja', 1)):
        resources = ET.Element('resources')
        for english, translations in sorted(catalog.items()):
            key = 'mod_' + hashlib.sha256(english.encode()).hexdigest()[:16]
            value = (translations[2] if len(translations) > 2 else english) if index is None else translations[index]
            ET.SubElement(resources, 'string', name=key).text = value.replace('\\', '\\\\').replace("'", "\\'")
        ET.indent(resources)
        path = root / locale / 'mod_strings.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(resources).write(path, encoding='utf-8', xml_declaration=True)
    out = source / 'util/src/commonMain/kotlin/coredevices/util/localization'
    out.mkdir(parents=True, exist_ok=True)
    entries = list(sorted(catalog.items()))
    # Keep JVM methods below the bytecode size limit.
    chunks = []
    for n in range(0, len(entries), 100):
        name = f'catalog{n // 100}'
        chunks.append(name)
        lines = ['package coredevices.util.localization\n', f'internal fun {name}() = mapOf(']
        for english, translations in entries[n:n + 100]:
            fallback = translations[2] if len(translations) > 2 else english
            lines.append(f'    {kotlin(english)} to Triple({kotlin(translations[0])}, {kotlin(translations[1])}, {kotlin(fallback)}),')
        lines.append(')\n')
        (out / f'Catalog{n // 100}.kt').write_text('\n'.join(lines), encoding='utf-8')
    (out / 'Catalog.kt').write_text('package coredevices.util.localization\n\ninternal val appCatalog = '
                                  + ' + '.join(f'{name}()' for name in chunks) + '\n', encoding='utf-8')
    for module in ('composeApp', 'pebble', 'util', 'experimental'):
        base = source / module / 'src/commonMain/composeResources'
        for original in (base / 'values').glob('*.xml'):
            if original.name == 'mod_strings.xml':
                continue
            for locale, index in (('values-zh', 0), ('values-ja', 1)):
                path = base / locale / original.name
                resources = ET.parse(path).getroot() if path.exists() else ET.Element('resources')
                existing = {entry.attrib.get('name') for entry in resources}
                for entry in ET.parse(original).getroot():
                    english = ''.join(entry.itertext())
                    if entry.attrib.get('name') in existing or entry.attrib.get('translatable') == 'false':
                        continue
                    if entry.tag != 'string' or english not in catalog:
                        print(f'New resource uses English fallback: {original}: {entry.attrib}')
                        continue
                    ET.SubElement(resources, 'string', **entry.attrib).text = catalog[english][index].replace("'", "\\'")
                ET.indent(resources)
                path.parent.mkdir(parents=True, exist_ok=True)
                ET.ElementTree(resources).write(path, encoding='utf-8', xml_declaration=True)


if __name__ == '__main__':
    main()
