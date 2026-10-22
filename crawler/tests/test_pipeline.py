import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from crawl import Crawler, canonical_url, same_domain_links
from clean import extract
from structure import extract_candidates, merge_reviewed
from embed import build_vectors


class Response:
    def __init__(self, status=200, body=b"", headers=None):
        self.status_code, self.body = status, body
        self.headers = headers or {"content-type": "text/html; charset=utf-8"}
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def iter_content(self, size):
        for start in range(0, len(self.body), size): yield self.body[start:start + size]


class CrawlerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.output = Path(self.temp.name)
        self.session = Mock()
        self.crawler = Crawler(self.output, self.session, sleep=Mock())
    def tearDown(self): self.temp.cleanup()

    def test_stdlib_struct_is_not_shadowed(self):
        import struct
        self.assertEqual(struct.unpack('!H', struct.pack('!H', 100)), (100,))

    def test_conditional_fetch_preserves_content_and_uses_etag(self):
        self.session.get.side_effect = [Response(404), Response(body=b'<html><article>test</article></html>', headers={"content-type": "text/html", "etag": '"version1"'}), Response(304)]
        first, html = self.crawler.fetch('https://example.org/article')
        second, cached = self.crawler.fetch('https://example.org/article')
        self.assertEqual(first['status'], 'fetched')
        self.assertEqual(second['status'], 'not_modified')
        self.assertEqual(html, cached)
        self.assertEqual(self.session.get.call_args.kwargs['headers']['If-None-Match'], '"version1"')
        self.assertTrue(self.crawler.sleep.called)

    def test_robots_denial_never_downloads_article(self):
        self.session.get.return_value = Response(body=b'User-agent: *\nDisallow: /private\n')
        result, _ = self.crawler.fetch('https://example.org/private/page')
        self.assertEqual(result['status'], 'robots_blocked')
        self.assertEqual(self.session.get.call_count, 1)

    def test_transient_failures_retry_and_binary_or_cross_host_redirects_fail(self):
        self.session.get.side_effect = [Response(404), Response(503), Response(body=b'<html>ok</html>')]
        self.assertEqual(self.crawler.fetch('https://example.org/a')[0]['status'], 'fetched')
        self.session.get.side_effect = [Response(302, headers={'Location': 'https://other.example/a'})]
        with self.assertRaisesRegex(ValueError, 'Cross-host'): self.crawler.fetch('https://example.org/b')
        self.session.get.side_effect = [Response(body=b'pdf', headers={'Content-Type': 'application/pdf'})]
        with self.assertRaisesRegex(ValueError, 'not HTML'): self.crawler.fetch('https://example.org/c')

    def test_page_limit_depth_and_duplicate_links_are_bounded(self):
        self.crawler.fetch = Mock(side_effect=lambda url: ({'url': url, 'status': 'fetched'}, '<a href="/b">B</a><a href="/c">C</a><a href="/b#x">B</a>'))
        report = self.crawler.run(['https://example.org/a'], depth=2, max_pages=2)
        self.assertEqual(len(report), 2)
        self.assertTrue((self.output / 'run_report.json').exists())
        self.assertEqual(same_domain_links('<a href="/a#x">a</a><a href="https://other.example/a">out</a>', 'https://example.org/'), ['https://example.org/a'])
        with self.assertRaises(ValueError): canonical_url('file:///tmp/a')

    def test_response_size_is_bounded_before_publication(self):
        self.session.get.side_effect = [Response(404), Response(body=b'a' * (2 * 1024 * 1024 + 1))]
        with self.assertRaisesRegex(ValueError, 'exceeds'): self.crawler.fetch('https://example.org/large')
        self.assertFalse(list(self.output.glob('*.html')))


class KnowledgeBuildTests(unittest.TestCase):
    def document(self, text):
        return {'url': 'https://example.org/article', 'title': '作物缺素诊断', 'text': text, 'crawledAt': '2026-10-22T00:00:00Z'}

    def reviewed(self):
        return {'id': 'reviewed-n', 'element': 'N', 'elementName': '氮', 'crops': ['水稻'],
                'sourceUrl': 'https://example.org/article', 'contentHash': 'test-hash',
                'needsReview': False, 'reviewedBy': 'test-reviewer', 'reviewedAt': '2026-10-22T00:00:00Z',
                'intro': '测试条目', 'colorPattern': ['老叶黄化'], 'morphology': [], 'agronomy': ['测试措施'],
                'organicPlan': [], 'chemicalPlan': [], 'safety': ['仅为测试，不用于施肥']}

    def test_cleaner_preserves_short_nutrient_headings_and_drops_navigation(self):
        title, text = extract('<title>诊断</title><nav>导航信息不应进入正文</nav><article><h2>一、缺氮</h2><p>水稻老叶首先发黄，植株生长矮小。</p></article>')
        self.assertIn('一、缺氮', text)
        self.assertNotIn('导航', text)

    def test_section_context_does_not_mix_comparison_or_unrelated_crop(self):
        document = self.document('一、缺氮\n水稻老叶黄化，分蘖减少。\n二、缺铁\n番茄新叶失绿，叶脉仍绿。\n缺氮与缺铁均可能发黄。')
        rows = {row['element']: row for row in extract_candidates(document)}
        self.assertIn('老叶', ''.join(rows['N']['symptoms']))
        self.assertNotIn('番茄', rows['N']['crops'])
        self.assertNotIn('缺氮与缺铁', ''.join(rows['Fe']['symptoms']))
        self.assertTrue(all(row['needsReview'] for row in rows.values()))

    def test_ids_are_stable_but_source_changes_invalidate_review_fingerprint(self):
        first = extract_candidates(self.document('缺氮：水稻老叶均匀黄化。'))[0]
        changed = extract_candidates(self.document('缺氮：水稻老叶均匀黄化，分蘖减少。'))[0]
        self.assertEqual(first['id'], changed['id'])
        self.assertNotEqual(first['contentHash'], changed['contentHash'])

    def test_merge_is_idempotent_and_validates_entire_batch_before_write(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'kb.jsonl'
            row = self.reviewed()
            self.assertEqual(merge_reviewed([row, row], output), 1)
            original = output.read_bytes()
            self.assertEqual(merge_reviewed([row], output), 0)
            pending = dict(row, id='new', needsReview=True)
            self.assertEqual(merge_reviewed([pending], output), 0)
            bad = dict(row, id='invalid', reviewedBy='')
            with self.assertRaises(ValueError): merge_reviewed([dict(row, id='valid-new'), bad], output)
            self.assertEqual(original, output.read_bytes())

    def test_review_requires_article_provenance_and_complete_plan_context(self):
        with tempfile.TemporaryDirectory() as directory:
            for changes in ({'sourceUrl': 'https://example.org/'}, {'chemicalPlan': [{'fertilizer': '测试', 'dosage': '1'}]}, {'reviewedAt': 'bad-date'}):
                with self.assertRaises(ValueError): merge_reviewed([dict(self.reviewed(), **changes)], Path(directory) / 'kb.jsonl')

    def test_new_review_can_update_existing_source_without_duplicate_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'kb.jsonl'
            row = self.reviewed()
            merge_reviewed([row], output)
            changed = dict(row, contentHash='new-source-hash', intro='更新后已审核的测试内容')
            self.assertEqual(merge_reviewed([changed], output), 1)
            records = [json.loads(line) for line in output.read_text(encoding='utf-8').splitlines()]
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0]['intro'], changed['intro'])

    def test_embedding_cache_and_failures_preserve_published_index(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'vectors.jsonl'
            entry = self.reviewed()
            embed = Mock(return_value=[[1.0, 2.0]])
            self.assertEqual(build_vectors([entry], embed, output, 'model', 2)['embedded'], 1)
            embed.reset_mock()
            self.assertEqual(build_vectors([entry], embed, output, 'model', 2)['reused'], 1)
            embed.assert_not_called()
            original = output.read_bytes()
            with self.assertRaises(ValueError): build_vectors([dict(entry, intro='changed')], lambda _: [], output, 'model', 2)
            self.assertEqual(original, output.read_bytes())
            with self.assertRaises(ValueError): build_vectors([dict(entry, intro='changed')], lambda _: [[float('nan'), 2]], output, 'model', 2)
            self.assertEqual(original, output.read_bytes())


if __name__ == '__main__': unittest.main()
