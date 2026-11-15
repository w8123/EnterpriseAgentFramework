<?php

declare(strict_types=1);

use ReachAI\Protocol;

return [
    'activity records searches and business failures, masked, then reports signed' => static function (): void {
        $transport = new FakeTransport();
        $transport->replies[] = [200, ['accepted' => 2, 'dropped' => 0, 'enabled' => true, 'captureSearch' => true]];
        $activity = reach($transport)->activity();
        $activity->recordRequest([
            'method' => 'GET', 'route' => '/customer/page', 'name' => '客户分页', 'status' => 200,
            'referer' => 'https://erp.example.com/customer?tab=1', 'query' => ['name' => '张三 13812345678'],
            'userId' => 7, 'userName' => '芋艿', 'startedAt' => microtime(true),
        ]);
        $activity->recordRequest([
            'method' => 'POST', 'route' => '/customer/create', 'status' => 200, 'contentType' => 'application/json',
            'body' => json_encode(['code' => 1002, 'msg' => '客户名称不能为空'], JSON_UNESCAPED_UNICODE),
            'origin' => 'https://erp.example.com', 'userId' => '7',
        ]);
        // scripts and server-to-server calls have neither Referer nor Origin
        same(null, $activity->recordRequest(['method' => 'GET', 'route' => '/customer/page', 'userId' => '7']), 'non-browser skipped');
        same(null, $activity->recordRequest(['method' => 'GET', 'route' => '/customer/page', 'referer' => 'x']), 'anonymous skipped');
        same(2, count($activity->pending()), 'two events pending');
        $activity->flush();
        same(1, count($transport->requests), 'one report');
        $request = $transport->requests[0];
        same('https://reachai.example.com/api/activity-ingress/projects/orders/events', $request['url'], 'ingress url');
        check(isset($request['headers']['X-ReachAI-Body-SHA256'], $request['headers']['X-ReachAI-Signature']), 'body signed');
        $body = $transport->lastJson();
        same('php-sdk', $body['sdk'], 'sdk name');
        same('SEARCH', $body['events'][0]['type'], 'search type');
        same('张三 138****5678', $body['events'][0]['keyword'], 'keyword masked');
        same('/customer', $body['events'][0]['page'], 'page path without query');
        same('GET /customer/page', $body['events'][0]['operation'], 'operation');
        same('FAILED', $body['events'][1]['outcome'], 'business failure');
        same('客户名称不能为空', $body['events'][1]['error'], 'business message');
        same([], $activity->pending(), 'queue emptied');
    },

    'activity stops when ReachAI switches collection off and never throws on failure' => static function (): void {
        $transport = new FakeTransport();
        $transport->replies[] = [200, ['accepted' => 0, 'enabled' => false]];
        $transport->replies[] = [500, 'boom'];
        $activity = reach($transport)->activity();
        $facts = ['method' => 'GET', 'route' => '/a', 'referer' => 'https://erp/a', 'userId' => '1'];
        $activity->recordRequest($facts);
        $activity->flush();
        $activity->recordRequest($facts);
        same([], $activity->pending(), 'nothing recorded after disable');

        $failing = new FakeTransport();
        $failing->replies[] = [500, 'boom'];
        $other = reach($failing)->activity();
        $other->recordRequest($facts);
        $other->flush();
        same(1, count($failing->requests), 'attempted once, failure swallowed');
    },

    'activity treats HTTP errors and success codes correctly' => static function (): void {
        $activity = reach()->activity(['successCodes' => [0]]);
        $base = ['method' => 'POST', 'route' => 'order/{id}/cancel', 'referer' => 'https://erp/o', 'userId' => '1'];
        same('HTTP 422', $activity->event($base + ['status' => 422])['error'], 'http status failure');
        same('业务返回码 200', $activity->event($base + ['body' => '{"code":200}'])['error'], '200 is not success here');
        same('SUCCESS', $activity->event($base + ['body' => '{"code":0,"data":[]}'])['outcome'], 'code 0 success');
        same('POST /order/{id}/cancel', $activity->event($base)['operation'], 'leading slash added');
        same(null, $activity->event(['method' => 'POST', 'route' => '/reachai/embed/token', 'referer' => 'x', 'userId' => '1']),
            'ReachAI endpoints are not business operations');
        check(Protocol::nowMillis() > 0, 'clock');
    },
];
