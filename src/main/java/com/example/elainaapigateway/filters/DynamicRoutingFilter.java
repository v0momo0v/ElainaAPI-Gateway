package com.example.elainaapigateway.filters;

import com.example.elainaapicommon.model.entity.InterfaceInfo;
import com.example.elainaapicommon.service.InnerInterfaceInfoService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
@Slf4j
public class DynamicRoutingFilter extends AbstractGatewayFilterFactory<Object> {
    @DubboReference
    private InnerInterfaceInfoService innerInterfaceInfoService;
    @Override
    public GatewayFilter apply(Object config) {
        log.info("这里进来了吗");
        return ((exchange, chain) -> {
            log.info("这里进来了");
            ServerHttpRequest request = exchange.getRequest();
            HttpHeaders headers = request.getHeaders();
            String body = headers.getFirst("body");
            Gson gson=new Gson();
            JsonParser jsonParser = new JsonParser();
            JsonObject jsonObject = jsonParser.parse(body).getAsJsonObject();
            Long id =Long.parseLong(jsonObject.get("id").getAsString());
            jsonObject.remove("id");
            String jsonString = gson.toJson(jsonObject);
            //从数据库中获取接口信息
            InterfaceInfo interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(id);
            String url = interfaceInfo.getUrl();
            URI uri=null;
            try {
                uri=new URI(url);
            } catch (URISyntaxException e) {
                throw new RuntimeException("URI转换错误",e);
            }
            ServerHttpRequest serverHttpRequest=request.mutate().uri(uri)
                    .method(request.getMethod())
                    .headers(httpHeaders -> {
                        httpHeaders.set("body",jsonString);
                    }).build();
            Route route=exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            Route newRoute=Route.async().asyncPredicate(route.getPredicate())
                    .filters(route.getFilters()).id(route.getId())
                    .order(route.getOrder()).uri(uri).build();
            exchange.getAttributes().put(GATEWAY_ROUTE_ATTR,newRoute);
            return chain.filter(exchange.mutate().request(serverHttpRequest).build());
        });
    }
}
