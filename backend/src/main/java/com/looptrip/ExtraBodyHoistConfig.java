package com.looptrip;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 网关只认请求体顶层的 enable_thinking，而 Spring AI 1.1.2 把 extraBody 序列化成嵌套字段，
 * 思考实际关不掉：思考与回答共用输出预算，思考写满后回答被截断或清空，落入 fact-fallback；
 * 抬高预算又会把生成时长顶到 read-timeout。这里在发出前把 extraBody 内容提升到顶层，真正关掉思考。
 */
@Configuration
public class ExtraBodyHoistConfig {

    @Bean
    RestClientCustomizer extraBodyHoistCustomizer(ObjectMapper mapper) {
        return builder -> builder.requestInterceptor(new ExtraBodyHoistInterceptor(mapper));
    }

    private static final class ExtraBodyHoistInterceptor implements ClientHttpRequestInterceptor {

        private final ObjectMapper mapper;

        ExtraBodyHoistInterceptor(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                ClientHttpRequestExecution execution) throws IOException {
            byte[] outgoing = body;
            if (request.getURI().getPath().endsWith("/chat/completions") && body.length > 0) {
                try {
                    outgoing = hoistExtraBody(body);
                } catch (IOException ignored) {
                    // 改写失败就原样发出，不让观测性修正影响主链路
                }
            }
            return execution.execute(request, outgoing);
        }

        private byte[] hoistExtraBody(byte[] body) throws IOException {
            ObjectNode tree = (ObjectNode) mapper.readTree(body);
            JsonNode extra = tree.remove("extraBody");
            if (extra == null) {
                extra = tree.remove("extra_body");
            }
            if (extra != null && extra.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = extra.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    tree.set(field.getKey(), field.getValue());
                }
            }
            // 不论 extra-body 是否绑定/序列化成功，顶层没有开关就补上，确保思考真的关掉。
            if (!tree.has("enable_thinking")) {
                tree.put("enable_thinking", false);
            }
            return mapper.writeValueAsBytes(tree);
        }
    }
}
