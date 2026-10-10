"""Small development retrieval diagnostic, local CPU only; not LLM accuracy."""
from datetime import datetime, timezone
import json
from pathlib import Path
import statistics
import time

from adapters.query_retrieval import MODEL_DIR, retrieve, encoder, load_corpus

# Originally separate from v1 examples; v2.1 includes 13 of these questions.
# Report overlap explicitly. This is not a held-out model benchmark.
CASES = [
    ('还留在公司的人有多少', 'headcount', None),
    ('现有员工规模', 'headcount', None),
    ('在册员工花名册', 'headcount', 'detail'),
    ('各部门现在分别有多少员工', 'headcount', 'org'),
    ('近半年员工规模走势', 'headcount', 'trend'),
    ('现在仍在岗的员工名单', 'headcount', 'detail'),
    ('八月份新来公司的人数', 'hire_count', None),
    ('九月办理入司的有几人', 'hire_count', None),
    ('刚加入公司的员工清单', 'hire_count', 'detail'),
    ('本季度招进来多少人', 'hire_count', None),
    ('上一个月报到人员列表', 'hire_count', 'detail'),
    ('今年新增员工数', 'hire_count', None),
    ('八月有多少人离开公司', 'leave_count', None),
    ('近期流失人员规模', 'leave_count', None),
    ('离职人员在哪些部门分布', 'leave_count', 'org'),
    ('按月看人员流出的变化', 'leave_count', 'trend'),
    ('各组织离开公司的员工数量', 'leave_count', 'org'),
    ('最近六个月离职数量走势', 'leave_count', 'trend'),
]


def main():
    catalog = {'metrics': [
        {'code': code, 'name': name, 'aliases': aliases, 'definition': definition,
         'version': 2, 'allowed_modes': ['scalar', 'org', 'trend', 'detail'], 'requires_period': code != 'headcount'}
        for code, name, aliases, definition in [
            ('headcount', '在职人数', ['在岗人数'], '统计时点仍在职的员工人数'),
            ('hire_count', '入职人数', ['新入职人数'], '统计期间内入职的员工人数'),
            ('leave_count', '离职人数', ['人员流失数量'], '统计期间内离职的员工人数'),
        ]], 'organizations': []}
    start = time.perf_counter()
    encoder()
    load_ms = (time.perf_counter() - start) * 1000
    example_questions = {row['question'].strip() for row in load_corpus()['examples']}
    report = {'purpose': 'development retrieval diagnostic, not end-to-end LLM evaluation',
        'catalog_source': 'synthetic three-metric catalog; live requests use authorized Java catalog',
        'model_manifest': json.loads((MODEL_DIR / 'hrchat-model.json').read_text(encoding='utf-8')),
        'model_load_ms': round(load_ms, 2), 'llm_calls': 0, 'count': len(CASES),
        'exact_example_overlap_count': sum(q.strip() in example_questions for q, _, _ in CASES),
        'modes': {}}
    for mode in ['lexical', 'hybrid']:
        rows = []
        for question, metric, query_mode in CASES:
            context, evidence = retrieve(question, catalog, mode=mode)
            if evidence['status'] != 'ok':
                raise RuntimeError('Retrieval did not execute: ' + evidence['status'])
            codes = [d['code'] for d in context['metadata']]
            examples = [d['draft'] for d in context['examples']]
            rows.append({'question': question, 'expected_metric': metric, 'expected_mode': query_mode,
                'exact_example_overlap': question.strip() in example_questions,
                'metric_hit_at_1': codes[:1] == [metric], 'metric_hit_at_2': metric in codes,
                'example_hit_at_2': any(d['metric_codes'] == [metric] and (d.get('query_mode') or 'scalar') == (query_mode or 'scalar') for d in examples),
                'retrieval': evidence})
        report['modes'][mode] = {'metric_hit_at_1': sum(r['metric_hit_at_1'] for r in rows) / len(rows),
            'metric_hit_at_2': sum(r['metric_hit_at_2'] for r in rows) / len(rows),
            'example_hit_at_2': sum(r['example_hit_at_2'] for r in rows) / len(rows),
            'median_ms': statistics.median(r['retrieval']['elapsed_ms'] for r in rows), 'cases': rows}
    run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    output = Path(__file__).resolve().parents[2] / 'docs/evaluation-runs' / ('retrieval-' + run_id)
    output.mkdir(parents=True)
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'report': str(output / 'report.json'), 'results': {
        mode: {k: v for k, v in data.items() if k != 'cases'} for mode, data in report['modes'].items()}}))


if __name__ == '__main__':
    main()
