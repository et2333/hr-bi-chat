"""Isolated real Java/Python/local embedding acceptance, scripted LLM, zero paid calls."""
from datetime import datetime, timezone
import json
import os
import socket
import subprocess
import sys
import urllib.request

from evals.api import JavaApi
from evals.launch_remote import wait_for
from evals.reference import ROOT


def main():
    java_url, python_url = 'http://127.0.0.1:18106', 'http://127.0.0.1:18107'
    for port in [18106, 18107]:
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    run_dir = ROOT / 'docs/evaluation-runs' / ('conversation-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    run_dir.mkdir(parents=True)
    env = dict(os.environ, LLM_PROFILE='mock', HRCHAT_RAG_MODE='hybrid', HRCHAT_QUERY_REPAIR_ENABLED='0',
        QUERY_BACKEND='java_mcp', JAVA_MCP_BASE_URL=java_url + '/mcp', HRCHAT_MCP_SERVICE_TOKEN='conversation-fixture-token',
        HRCHAT_DEMO_NOW='2026-09-28', PYTHONIOENCODING='utf-8')
    jar = ROOT / 'hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar'
    commands = [
        ('python', [sys.executable, '-m', 'uvicorn', 'evals.conversation_fixture_gateway:app', '--host', '127.0.0.1', '--port', '18107'], ROOT / 'hrchat-ai', python_url + '/health'),
        ('java', ['java', '-jar', str(jar), '--spring.profiles.active=' + ('local,j2' if '--j2' in sys.argv else 'local'), '--server.address=127.0.0.1', '--server.port=18106',
          '--hrchat.ai.runtime=remote', '--hrchat.ai.remote-base-url=' + python_url, '--logging.level.root=WARN'], ROOT / 'hrchat-server', java_url + '/actuator/health')]
    processes, logs = [], []
    report = {'kind': 'scripted LLM + real Java + real local embedding', 'paid_model_calls': 0, 'checks': {}}
    try:
        for name, command, cwd, health in commands:
            log = (run_dir / (name + '.log')).open('wb')
            logs.append(log)
            process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            processes.append(process)
            wait_for(health, process)
        api = JavaApi(java_url, 45)
        session = api.session('hr04')
        def get(path):
            status, raw = api.request(path, 'hr04')
            assert status == 200, (status, raw)
            return json.loads(raw)['data']
        def interrupt(result):
            return next(e['payload'] for e in result['events'] if e['event'] == 'INTERRUPT')
        initial = api.ask(session, 'hr04', {'question': '人员变动情况'})
        report['initial'] = initial
        q = interrupt(initial)
        ask_id = q.get('askId') or q['ask_id']
        question = q['questions'][0]
        selected = api.clarify(session, 'hr04', ask_id, question.get('questionId') or question['question_id'], 'leave_count')
        report['metric_selected'] = selected
        assert selected['status'] == 'CLARIFYING', selected
        q = interrupt(selected)['questions'][0]
        final = api.clarify(session, 'hr04', ask_id, q.get('questionId') or q['question_id'], 'time:LAST_MONTH')
        assert final['status'] == 'COMPLETED', final
        history = get('/api/v1/chat/asks/' + ask_id + '/history')
        assert len([e for e in history if e['kind'] == 'selection']) == 2, history
        assert len([e for e in history if e['kind'] == 'clarification']) == 2, history
        assert get(f'/api/v1/chat/sessions/{session}/turns')
        report['history'] = history
        report['checks']['two_round_clarification_persisted'] = True
        source = api.ask(session, 'hr04', {'question': '上月研发中心离职人数'})
        report['source'] = source
        assert source['evidence']['retrieval']['status'] == 'ok', source
        assert source['evidence']['retrieval']['distractor_count'] == 0, source
        expected_scope = {2, 3, 4, 9, 10} if '--j2' in sys.argv else {2, 3, 4}
        assert set(source['evidence']['execution']['effective_org_ids']) == expected_scope, source
        report['checks']['optional_seed_scope_and_live_retrieval_boundary'] = True
        events = [e['event'] for e in source['events']]
        assert events.index('PROGRESS') < events.index('ANSWER_DONE') and 'USAGE' in events
        report['checks']['real_embedding_and_progress_on_public_path'] = True
        prepared = api.ask(session, 'hr04', {'question': '分析变化'})
        report['prepared'] = prepared
        assert prepared['answer']['analysisPreparation']['sourceAskId'] == source['answer']['askId'], prepared
        assert 'taskId' not in prepared['answer']['analysisPreparation']
        report['checks']['analysis_prepares_previous_answer_without_starting'] = True
        other = api.session('hr04')
        assert api.ask(other, 'hr04', {'question': '分析变化'})['status'] != 'COMPLETED'
        report['checks']['analysis_cannot_borrow_other_session'] = True
        for question in ['按组织对比平均薪酬', '薪酬对比', '薪资对比']:
            assert api.ask(other, 'hr04', {'question': question})['status'] == 'UNSUPPORTED'
        report['checks']['salary_not_silently_substituted'] = True
        if '--j2' in sys.argv:
            org_session = api.session('hr04')
            first = api.ask(org_session, 'hr04', {'question': '研发部离职人数'})
            report['organization_initial'] = first
            payload = interrupt(first)
            org_ask_id = payload.get('askId') or payload['ask_id']
            question = payload['questions'][0]
            options = question['options']
            assert {o.get('optionId') or o.get('option_id') for o in options} == {'9', '10'}, options
            assert any('RD2-DEV' in o['label'] for o in options), options
            chosen = api.clarify(org_session, 'hr04', org_ask_id,
                question.get('questionId') or question['question_id'], '10')
            report['organization_selected'] = chosen
            assert chosen['status'] == 'CLARIFYING', chosen
            question = interrupt(chosen)['questions'][0]
            answer = api.clarify(org_session, 'hr04', org_ask_id,
                question.get('questionId') or question['question_id'], 'time:LAST_MONTH')
            report['organization_answer'] = answer
            assert answer['status'] == 'COMPLETED', answer
            plan = answer['evidence']['execution']['query_plan']
            assert plan['org_scope']['org_id'] == '10', plan
            assert plan['time_range']['start'] == '2026-08-01', plan
            org_history = get('/api/v1/chat/asks/' + org_ask_id + '/history')
            assert len([e for e in org_history if e['kind'] == 'selection']) == 2, org_history
            report['organization_history'] = org_history
            report['checks']['homonym_choice_then_period_persisted'] = True
        if '--browser' in sys.argv:
            cli = ROOT / 'hrchat-web/node_modules/@playwright/test/cli.js'
            browser = subprocess.run(['node', str(cli), 'test', 'e2e/conversation.spec.ts'], cwd=ROOT / 'hrchat-web',
                env=dict(env, HRCHAT_API_TARGET=java_url, CONVERSATION_FIXTURE_E2E='1',
                         J2_FIXTURE_E2E='1' if '--j2' in sys.argv else '0'), timeout=180)
            assert browser.returncode == 0
            report['checks']['browser_history_reload_and_analysis_confirmation'] = True
        report['passed'] = True
    finally:
        (run_dir / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        for process in reversed(processes):
            process.terminate()
            try: process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for log in logs: log.close()
        print(json.dumps({'report': str(run_dir / 'report.json'), 'checks': report['checks'], 'passed': report.get('passed', False)}))


if __name__ == '__main__':
    main()
