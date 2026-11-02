"""验证体验初始化的 Agent 修订协议；所有 API 均为内存夹具，不连接服务。"""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


class QuickstartAgentRevisionTest(unittest.TestCase):
    def setUp(self):
        source = Path(__file__).resolve().parents[1] / "deploy/quickstart/init/bootstrap.py"
        spec = importlib.util.spec_from_file_location("reachai_bootstrap_fixture", source)
        self.bootstrap = importlib.util.module_from_spec(spec)
        with patch.dict("os.environ", {"LLM_API_KEY": "", "REACHAI_ADMIN_PASSWORD": "test-only"}):
            spec.loader.exec_module(self.bootstrap)
        self.calls = []
        self.project = {"id": 7}
        self.agent = {"id": "agent-fixture", "keySlug": self.bootstrap.AGENT_SLUG}
        self.fail_path = None
        self.bad_context = False
        self.bad_save = False
        self.bad_publish = False
        self.bootstrap.log = lambda message: None
        self.bootstrap.call = self.call

    def view(self, revision, status=None):
        return {"agentId": "agent-fixture", "projectId": 7, "projectCode": self.bootstrap.PROJECT_CODE,
                "revision": revision * 64,
                "base": None if status is None else {"agentId": "agent-fixture", "id": 4, "versionNo": 1, "status": status}}

    def call(self, method, path, body=None, **kwargs):
        self.calls.append((method, path, body))
        if path == self.fail_path:
            raise self.bootstrap.ApiError(409, "配置已被其他人修改")
        if path == "/api/agents":
            return [self.agent]
        if path.endswith("/config-versions"):
            return []
        if path.endswith("/draft-context"):
            context = self.view("a")
            if self.bad_context:
                context["projectCode"] = "foreign-project"
            return context
        if path.endswith("/draft-if-unchanged"):
            return {} if self.bad_save else self.view("b", "DRAFT")
        if path.endswith("/publish-if-unchanged"):
            return self.view("c", "DRAFT" if self.bad_publish else "ACTIVE")
        raise AssertionError("unexpected fixture request: " + path)

    def test_saves_and_publishes_with_the_exact_revisions_returned_by_the_server(self):
        result = self.bootstrap.ensure_agent(self.project, "model", [])
        self.assertEqual(self.agent, result)
        writes = [call for call in self.calls if call[0] != "GET"]
        self.assertEqual(2, len(writes))
        self.assertTrue(writes[0][1].endswith("/draft-if-unchanged"))
        self.assertEqual("a" * 64, writes[0][2]["baseRevision"])
        self.assertEqual("model", writes[0][2]["changes"]["modelInstanceId"])
        self.assertTrue(writes[1][1].endswith("/4/publish-if-unchanged"))
        self.assertEqual({"baseRevision": "b" * 64}, writes[1][2])

    def test_conflict_stops_without_rereading_or_replaying_a_write(self):
        for suffix, expected_writes in (("draft-if-unchanged", 1), ("4/publish-if-unchanged", 2)):
            with self.subTest(suffix=suffix):
                self.calls.clear()
                self.fail_path = "/api/agents/agent-fixture/config-versions/" + suffix
                with self.assertRaises(self.bootstrap.ApiError) as failure:
                    self.bootstrap.ensure_agent(self.project, "model", [])
                self.assertEqual(409, failure.exception.status)
                self.assertEqual(1, sum(path.endswith("/draft-context") for _, path, _ in self.calls))
                self.assertEqual(expected_writes, sum(method != "GET" for method, _, _ in self.calls))

    def test_wrong_scope_or_unconfirmed_results_never_continue_to_the_next_write(self):
        for flag, expected_writes in (("bad_context", 0), ("bad_save", 1), ("bad_publish", 2)):
            with self.subTest(flag=flag):
                self.calls.clear()
                setattr(self, flag, True)
                with self.assertRaises(self.bootstrap.ApiError):
                    self.bootstrap.ensure_agent(self.project, "model", [])
                self.assertEqual(expected_writes, sum(method != "GET" for method, _, _ in self.calls))
                setattr(self, flag, False)

    def test_without_a_model_it_saves_a_draft_but_does_not_publish(self):
        self.bootstrap.ensure_agent(self.project, None, [])
        self.assertEqual(1, sum(method != "GET" for method, _, _ in self.calls))
        self.assertFalse(any(path.endswith("/publish-if-unchanged") for _, path, _ in self.calls))


if __name__ == "__main__":
    unittest.main()
