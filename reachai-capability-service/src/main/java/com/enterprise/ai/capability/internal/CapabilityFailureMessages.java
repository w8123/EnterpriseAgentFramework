package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.SSLException;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把调用业务系统时的技术失败翻译成员工和 AI 工具能看懂的中文提示：发生了什么、下一步做什么。
 * 只带业务系统地址（协议 + 主机 + 端口），不带路径、查询参数和上游响应体。
 */
final class CapabilityFailureMessages {

    private static final Pattern REQUEST_ORIGIN = Pattern.compile("(?i)\\b(https?://[^/\\s\"'?#]+)");

    private CapabilityFailureMessages() {
    }

    static String technicalFailure(Throwable failure, CapabilityInvocationFailureCategory category) {
        RestClientResponseException response = find(failure, RestClientResponseException.class);
        if (response != null) {
            return httpFailure(response.getStatusCode().value());
        }
        String target = target(failure);
        if (find(failure, UnknownHostException.class) != null) {
            return "找不到业务系统" + target + "：地址无法解析。请检查「业务系统」里登记的访问地址是否写错，"
                    + "以及 ReachAI 所在服务器能否解析这个域名。";
        }
        if (find(failure, SSLException.class) != null) {
            return "和业务系统" + target + "建立 HTTPS 连接失败：证书无效或协议不匹配。"
                    + "请检查业务系统的证书，或确认访问地址的 http / https 是否写对。";
        }
        if (category == CapabilityInvocationFailureCategory.TIMEOUT) {
            return "业务系统" + target + "在规定时间内没有响应。请稍后重试；"
                    + "一直超时请检查业务系统是否卡住，或 ReachAI 到业务系统的网络是否通畅。";
        }
        if (category == CapabilityInvocationFailureCategory.CONNECTIVITY) {
            return "连不上业务系统" + target + "：对方拒绝连接或网络不通。请确认业务系统已经启动，"
                    + "并检查「业务系统」里登记的访问地址和端口是否正确。";
        }
        String detail = failure == null ? null : failure.getMessage();
        return detail == null || detail.isBlank()
                ? "调用业务系统时出错，请稍后重试；一直失败请到运行中心查看这次调用的记录。"
                : "调用业务系统时出错：" + detail;
    }

    static String httpFailure(int status) {
        if (status == 401 || status == 403) {
            return "业务系统拒绝了这次调用（HTTP " + status + "）：调用身份或接入凭证无效，或当前账号没有这个操作的权限。"
                    + "请检查业务系统的接入凭证，或换有权限的账号重试。";
        }
        if (status == 404) {
            return "业务系统里找不到这个接口（HTTP 404）：接口可能已改名或下线。请在「业务系统」里重新同步接口后再试。";
        }
        if (status == 400 || status == 422) {
            return "业务系统认为这次传入的参数不正确（HTTP " + status + "）。请检查参数后重试。";
        }
        if (status == 429) {
            return "业务系统暂时限流（HTTP 429），请稍后重试。";
        }
        if (status >= 500) {
            return "业务系统内部出错（HTTP " + status + "）。请稍后重试；一直失败请联系业务系统负责人查看日志。";
        }
        return "业务系统返回了错误（HTTP " + status + "）。请到运行中心查看这次调用的记录。";
    }

    private static String target(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                Matcher origin = REQUEST_ORIGIN.matcher(message);
                if (origin.find()) {
                    return "（" + origin.group(1) + "）";
                }
            }
            if (current.getCause() == current) break;
        }
        return "";
    }

    private static <T extends Throwable> T find(Throwable failure, Class<T> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}
