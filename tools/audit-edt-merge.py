"""Native EDT merge integration audit using NEW disposable workspace projects.

Requires --apply, running EDT Bridge, and a NEW output directory underneath
.runs/creation-audit. Uses no infobase, Git merge or desktop input. Cleans its own
marked fixtures in finally. Credentials stay in the local MCP client only.
"""
from pathlib import Path
import argparse, hashlib, json, runpy, time, urllib.error, xml.etree.ElementTree as ET

Client = runpy.run_path(str(Path(__file__).with_name('audit-edt-creation.py')))['Client']
CONTENT = {
    'Main': 'Function Calculate() Export\r\n    Return MainOnly();\r\nEndFunction\r\n\r\nFunction MainOnly() Export\r\n    Return 1;\r\nEndFunction\r\n',
    'Other': 'Function Calculate() Export\r\n    Return OtherOnly();\r\nEndFunction\r\n\r\nFunction OtherOnly() Export\r\n    Return 2;\r\nEndFunction\r\n',
    'Base': 'Function Calculate() Export\r\n    Return 0;\r\nEndFunction\r\n',
}
CUSTOM = 'Function Calculate() Export\r\n    Return MainOnly() + OtherOnly() + 10;\r\nEndFunction\r\n'


def wait_until(read, predicate, seconds=120):
    deadline = time.monotonic() + seconds
    while True:
        value = read()
        if predicate(value):
            return value
        if time.monotonic() >= deadline:
            raise TimeoutError(str(value))
        time.sleep(.5)


def fingerprints(project):
    return {str(f.relative_to(project)): hashlib.sha256(f.read_bytes()).hexdigest()
            for f in sorted((project / 'src').rglob('*')) if f.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', default=str(Path.home() / '.codex/config.toml'))
    parser.add_argument('--server', default='edt_bridge')
    parser.add_argument('--output', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    out = Path(args.output).resolve()
    if '/.runs/creation-audit/' not in out.as_posix() or out.exists():
        parser.error('Use a NEW directory under .runs/creation-audit')
    if not args.apply:
        print('Add --apply to create, merge and clean three disposable projects.')
        return
    deadline = time.monotonic() + 120
    while True:
        try:
            c = Client(args.config, args.server)
            break
        except urllib.error.HTTPError:
            raise
        except urllib.error.URLError:
            if time.monotonic() > deadline:
                raise
            time.sleep(1)
    wait_until(lambda: c.rpc('tools/list', {})['tools'], lambda ts: any(t['name'] == 'edt_window' for t in ts))
    out.mkdir(parents=True)
    report = {'checks': [], 'cleanup': [], 'manual_gui_input_tested': False}
    fixtures, sessions = {}, []
    suffix = str(time.time_ns())[-10:]

    def save():
        (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    def check(label, value=True):
        assert value, label
        report['checks'].append(label)
        save()
        print('PASS', label, flush=True)

    def job(result):
        value = wait_until(lambda: c.call('edt_operation_status', {'job_id': result['job_id']}),
                           lambda x: x['state'] in ('DONE', 'FAILED', 'CANCELLED'))
        assert value['state'] == 'DONE', value
        return value

    def compare():
        value = c.call('edt_compare', {'action': 'start', 'main_project': fixtures['Main']['name'],
                       'other_project': fixtures['Other']['name'], 'ancestor_project': fixtures['Base']['name'],
                       'scope': ['CommonModule.MergeAudit', 'Catalog.MergeItems'], 'matching': 'NAME'})
        sid = value['session_id']
        sessions.append(sid)
        wait_until(lambda: c.call('edt_compare', {'action': 'status', 'session_id': sid}),
                   lambda x: x['status'] == 'COMPARISON_PROCESS_FINISHED')
        tree = c.call('edt_compare', {'action': 'tree', 'session_id': sid, 'changes_only': False, 'limit': 500})
        return sid, tree['nodes']

    def blocked(label, operation, expected):
        try:
            operation()
        except RuntimeError as error:
            check(label, expected.lower() in str(error).lower())
        else:
            raise AssertionError(label + ': operation was not blocked')

    try:
        report['bridge'] = c.call('edt_bridge_status', {})
        report['perspectives_before'] = c.call('edt_window', {'action': 'state'})
        for side, content in CONTENT.items():
            name = 'BridgeCreationMerge' + suffix + side
            path = out / side.lower() / 'project'
            path.parent.mkdir()
            job(c.call('edt_project_create', {'project': name, 'configuration_name': 'MergeAudit',
                                             'version': '8.5.1', 'project_path': str(path)}))
            wait_until(lambda: (path / 'src/Configuration/Configuration.mdo').exists(), bool)
            configuration = ET.parse(path / 'src/Configuration/Configuration.mdo').getroot()
            (path / '.edt-bridge-creation-audit.json').write_text(json.dumps({
                'purpose': 'edt-bridge-creation-coverage', 'project': name,
                'configuration_uuid': configuration.get('uuid')}), encoding='utf-8')
            fixtures[side] = {'name': name, 'path': path}
            deadline = time.monotonic() + 120
            while True:
                try:
                    c.call('edt_creation_defaults', {'project': name, 'class_name': 'CommonModule', 'fields': ['server']})
                    break
                except RuntimeError:
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(.5)
            c.mutate('create_metadata', {'project': name, 'kind': 'CommonModule', 'name': 'MergeAudit',
                                        'properties': {'server': True, 'clientOrdinaryApplication': True, 'clientManagedApplication': False, 'externalConnection': True}})
            c.mutate('ensure_module_artifact', {'project': name, 'object_fqn': 'CommonModule.MergeAudit',
                     'module_kind': 'module', 'create_if_missing': True, 'initial_content': content})
            c.mutate('create_metadata', {'project': name, 'kind': 'Catalog', 'name': 'MergeItems',
                                        'properties': {'descriptionLength': 40 if side == 'Other' else 25}})
        before = {side: fingerprints(f['path']) for side, f in fixtures.items()}
        mainpath = fixtures['Main']['path']
        identities = {str(p.relative_to(mainpath)): ET.parse(p).getroot().get('uuid')
                      for p in mainpath.rglob('*.mdo')}
        sid, nodes = compare()
        calc = next(n for n in nodes if n.get('main_name') == 'Calculate')
        other = next(n for n in nodes if n.get('other_name') == 'OtherOnly')
        length = next(n for n in nodes if n['path'] == 'FT:descriptionLength')
        for node in (other, length):
            detail = c.call('edt_compare', {'action': 'details', 'session_id': sid, 'node_id': node['node_id']})
            if node is length:
                check('scalar_sides_25_40_25', detail['sides'] == {'MAIN': 25, 'OTHER': 40, 'COMMON_ANCESTOR': 25})
            c.call('edt_merge_plan', {'session_id': sid, 'node_id': node['node_id'], 'rule': 'take_other',
                                     'expected_node_sha256': detail['node_sha256']})
        a = {'session_id': sid, 'node_id': calc['node_id']}
        detail = c.call('edt_merge_result', a)
        check('method_region_excludes_other_functions', 'Function MainOnly' not in detail['sides']['MAIN'])
        c.call('edt_merge_result', {**a, 'action': 'write', 'expected_node_sha256': detail['node_sha256'],
                                   'content': CUSTOM.replace(' + 10', '')})
        handoff_editors = set()
        def handoff_once():
            result = c.call('edt_compare', {'action': 'handoff', **a,
                           'reason': '\u041f\u0440\u043e\u0432\u0435\u0440\u0438\u0442\u044c MainOnly() + OtherOnly().'})
            handoff_editors.add(result['editor_input_uuid'])
            return result
        handoff = wait_until(handoff_once, lambda x: x['highlighted'] and x.get('review_column_installed'))
        check('handoff_retry_does_not_duplicate_loading_editor', len(handoff_editors) == 1)
        report['handoff'] = handoff
        repeated = c.call('edt_compare', {'action': 'handoff', **a})
        check('handoff_reuses_existing_editor', repeated['editor_reused'] and repeated['editor_input_uuid'] == handoff['editor_input_uuid'])
        check('same_native_session_highlight_and_question_column', handoff['session_id'] == sid)
        check('handoff_does_not_write_sources', before == {side: fingerprints(f['path']) for side, f in fixtures.items()})
        # Capture only the verified native EDT process/window. No activation/input.
        time.sleep(2)  # Native editor layout/rendering is asynchronous.
        windows = c.call('edt_process_snapshot', {'action': 'list', 'process_id': handoff['process_id']})
        process = next(p for p in windows['processes'] if p['process_id'] == handoff['process_id'])
        window = next(w for w in process['windows'] if w['visible'] and w['owner_window_id'] == '0x0' and 'Development Tools' in w['title'])
        report['screenshot'] = c.call('edt_process_snapshot', {'action': 'snapshot', 'process_id': process['process_id'],
                                    'process_started_at': process['process_started_at'], 'window_id': window['window_id']})
        oldplan = c.call('edt_merge_plan', {'session_id': sid})
        detail = c.call('edt_merge_result', a)
        patched = c.call('edt_merge_result', {**a, 'action': 'patch', 'expected_node_sha256': detail['node_sha256'],
                         'old_text': 'MainOnly() + OtherOnly()', 'new_text': 'MainOnly() + OtherOnly() + 10'})
        check('reread_native_custom_edit_after_handoff', patched['result'] == CUSTOM)
        blocked('stale_plan_rejected', lambda: c.call('edt_merge_apply', {'session_id': sid, 'expected_plan_sha256': oldplan['plan_sha256']}), 'STALE')
        bad = c.call('edt_merge_result', {**a, 'action': 'write', 'expected_node_sha256': patched['node_sha256'],
                     'content': 'Function Calculate() Export\r\n    Return (;\r\nEndFunction\r\n'})
        check('native_parser_finds_bad_custom', bool(bad['syntax_errors']))
        invalid = c.call('edt_merge_plan', {'session_id': sid})
        blocked('invalid_custom_blocks_apply', lambda: c.call('edt_merge_apply', {'session_id': sid, 'expected_plan_sha256': invalid['plan_sha256']}), 'syntax errors')
        c.call('edt_merge_result', {**a, 'action': 'write', 'expected_node_sha256': bad['node_sha256'], 'content': CUSTOM})
        plan = c.call('edt_merge_plan', {'session_id': sid})
        source = fixtures['Other']['path'] / 'src/CommonModules/MergeAudit/Module.bsl'
        original = source.read_bytes()
        try:
            source.write_bytes(original + b'\r\n// guard probe\r\n')
            blocked('changed_source_blocks_apply', lambda: c.call('edt_merge_apply', {'session_id': sid, 'expected_plan_sha256': plan['plan_sha256']}), 'SOURCE_CHANGED')
        finally:
            source.write_bytes(original)
        report['apply'] = c.call('edt_merge_apply', {'session_id': sid, 'expected_plan_sha256': plan['plan_sha256']})
        check('native_merge_accepted', report['apply']['accepted'])
        status = wait_until(lambda: c.call('edt_compare', {'action': 'status', 'session_id': sid}),
                            lambda x: x['applied'] or x.get('failure') or x['status'] == 'COMPARISON_MERGE_PROCESS_CANCELLED')
        report['final_status'] = status
        check('native_finished_remembered_after_discard', status['applied'] and status['status'] == 'MERGE_PROCESS_FINISHED')
        expected = (mainpath / 'src/CommonModules/MergeAudit/Module.bsl').read_text(encoding='utf-8')
        check('both_methods_and_edited_link_persisted', all(x in expected for x in ('Return MainOnly() + OtherOnly() + 10;', 'Function MainOnly', 'Function OtherOnly')))
        catalog = ET.parse(mainpath / 'src/Catalogs/MergeItems/MergeItems.mdo').getroot()
        check('metadata_length_persisted_40', catalog.findtext('descriptionLength') == '40')
        check('target_uuids_preserved', all(ET.parse(mainpath / p).getroot().get('uuid') == value for p, value in identities.items()))
        check('source_and_ancestor_unchanged', all(fingerprints(fixtures[s]['path']) == before[s] for s in ('Other', 'Base')))
        report['diagnostics'] = c.call('get_diagnostics', {'scope': 'project', 'project_name': fixtures['Main']['name'],
                                      'object': 'MergeAudit', 'severity': 'error', 'wait_ms': 2000})
        check('merged_module_has_no_error_diagnostics', '**ERROR**' not in report['diagnostics'].get('text', ''))
        sid2, nodes2 = compare()
        historical = c.call('edt_compare', {'action': 'status', 'session_id': sid})
        check('old_status_does_not_bind_new_native_session', historical['applied'] and not historical['native_session_present'] and historical['session_replaced'])
        blocked('old_id_cannot_edit_new_comparison', lambda: c.call('edt_merge_result', a), 'not editable')
        c.call('edt_compare', {'action': 'close', 'session_id': sid})
        sessions.remove(sid)
        still_ready = c.call('edt_compare', {'action': 'status', 'session_id': sid2})
        check('closing_old_id_keeps_new_comparison', still_ready['native_session_present'] and still_ready['status'] == 'COMPARISON_PROCESS_FINISHED')
        calc2 = next(n for n in nodes2 if n.get('main_name') == 'Calculate')
        reread = c.call('edt_compare', {'action': 'details', 'session_id': sid2, 'node_id': calc2['node_id']})
        check('fresh_comparison_reads_edited_result', 'MainOnly() + OtherOnly() + 10' in reread['sides']['MAIN'])
        same = next((n for n in nodes2 if n['path'] == 'FT:descriptionLength'), None)
        check('fresh_comparison_metadata_equal', same is None or not same['changed'])
        report['perspectives_after'] = c.call('edt_window', {'action': 'state'})
        check('open_perspectives_preserved', report['perspectives_before']['window']['open_perspectives'] == report['perspectives_after']['window']['open_perspectives'])
    finally:
        report['session_cleanup_errors'] = []
        for sid in reversed(sessions):
            try:
                c.call('edt_compare', {'action': 'close', 'session_id': sid})
            except RuntimeError as error:
                report['session_cleanup_errors'].append(str(error))
        for side, fixture in fixtures.items():
            try:
                payload = {'project': fixture['name'], 'expected_project_path': str(fixture['path'])}
                job(c.call('edt_creation_audit_cleanup', {**payload, 'dry_run': True}))
                job(c.call('edt_creation_audit_cleanup', {**payload, 'dry_run': False}))
                assert not fixture['path'].exists()
                report['cleanup'].append({'side': side, 'removed': True})
            except Exception as error:
                report['cleanup'].append({'side': side, 'removed': False, 'error': str(error)})
        save()
    assert all(x['removed'] for x in report['cleanup']), 'Cleanup incomplete: inspect report.json'
    print(json.dumps({'checks': len(report['checks']), 'cleaned_projects': len(report['cleanup']), 'report': str(out / 'report.json')}))


if __name__ == '__main__':
    main()
