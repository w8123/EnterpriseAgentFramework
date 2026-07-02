#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
import urllib.request


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        charset = response.headers.get_content_charset() or "utf-8"
        return json.loads(response.read().decode(charset))


def post_json(url, payload):
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        charset = response.headers.get_content_charset() or "utf-8"
        return json.loads(response.read().decode(charset))


def main():
    parser = argparse.ArgumentParser(description="Verify ReachAI onboarding manifest and optional SDK access check.")
    parser.add_argument("--manifest-url", required=True)
    parser.add_argument("--run-check", action="store_true")
    parser.add_argument("--api-asset-id", type=int)
    parser.add_argument("--args-json", default="{}")
    parser.add_argument("--gateway-base-url")
    parser.add_argument("--embed-token-path")
    parser.add_argument("--report-url", help="Access session step report URL. Replace {stepKey} automatically when present.")
    parser.add_argument("--step-key", help="Access session step key to report.")
    parser.add_argument("--status", default="PASS", help="Step status: TODO, RUNNING, PASS, WARN, FAIL, or SKIPPED.")
    parser.add_argument("--message", default="ReachAI onboarding helper reported this step.")
    parser.add_argument("--file", action="append", default=[], help="Changed file path to include in progress evidence.")
    parser.add_argument("--evidence-json", default="{}", help="Additional evidence JSON for the step report.")
    parser.add_argument("--reported-by", default="reachai-onboarding-helper")
    args = parser.parse_args()

    try:
        manifest = fetch_json(args.manifest_url)
        print("manifest.schema=" + str(manifest.get("schema")))
        print("project.code=" + str(manifest.get("project", {}).get("projectCode")))
        print("credential.configured=" + str(manifest.get("project", {}).get("registryCredentialConfigured")))
        print("secret.env=" + str(manifest.get("security", {}).get("appSecretEnv")))

        if args.run_check:
            payload = {
                "apiAssetId": args.api_asset_id,
                "args": json.loads(args.args_json),
                "gatewayBaseUrl": args.gateway_base_url,
                "embedTokenPath": args.embed_token_path,
            }
            result = post_json(manifest["endpoints"]["sdkAccessCheckUrl"], payload)
            print("sdkAccessCheck.overallStatus=" + str(result.get("overallStatus")))
            print(json.dumps(result, ensure_ascii=False, indent=2))

        if args.report_url and args.step_key:
            evidence = json.loads(args.evidence_json)
            report_url = args.report_url.replace("{stepKey}", args.step_key)
            payload = {
                "status": args.status,
                "message": args.message,
                "files": args.file,
                "evidence": evidence,
                "reportedBy": args.reported_by,
            }
            result = post_json(report_url, payload)
            print("accessSession.sessionId=" + str(result.get("sessionId")))
            print("accessSession.status=" + str(result.get("status")))
    except (urllib.error.URLError, KeyError, json.JSONDecodeError) as exc:
        print("ReachAI verification failed: " + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
