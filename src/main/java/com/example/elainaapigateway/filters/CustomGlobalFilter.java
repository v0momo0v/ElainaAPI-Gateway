package com.example.elainaapigateway.filters;

import com.example.elainaapicommon.model.entity.InterfaceInfo;
import com.example.elainaapicommon.model.entity.User;
import com.example.elainaapicommon.service.InnerInterfaceInfoService;
import com.example.elainaapicommon.service.InnerUserInterfaceInfoService;
import com.example.elainaapicommon.service.InnerUserService;
import com.example.elainaapisdk.utils.SignUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {
    @DubboReference
    private InnerInterfaceInfoService innerInterfaceInfoService;
    @DubboReference
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;
    @DubboReference
    private InnerUserService innerUserService;
    private static final List<String> IP_WHITE_LIST= Arrays.asList("127.0.0.1");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //请求日志
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        System.out.println("所有的请求参数："+request.toString());
        log.info("请求id："+request.getId());
        String method = request.getMethod().toString();
        log.info("URI:"+request.getURI());
        log.info("请求标识"+request.getId());
        log.info("请求路径"+request.getPath().value());
        log.info("请求方法"+request.getMethod());
        log.info("请求参数"+request.getQueryParams());
        String sourceAddress = request.getLocalAddress().getHostString();
        log.info("请求来源地址"+sourceAddress);
        log.info("请求地址"+request.getRemoteAddress());
        //访问控制，黑白名单
        ServerHttpResponse response = exchange.getResponse();
        if(!IP_WHITE_LIST.contains(sourceAddress)){
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }
        //用户鉴权
        HttpHeaders headers = request.getHeaders();
        String accessKey = headers.getFirst("accessKey");
        log.info("accessKey"+accessKey);
        String nonce = headers.getFirst("nonce");
        String timestamp = headers.getFirst("timestamp");
        String sign = headers.getFirst("sign");
        String body = headers.getFirst("body");
        //用户是否存在
        User invokeUser=null;
        try {
            invokeUser=innerUserService.getInvokeUser(accessKey);
        }catch (Exception e){
            log.error("getInvokeUser error",e);
        }
        if(invokeUser==null){
            return handleNoAuth(response);
        }
        //随机数不能大于10000
        if(Long.parseLong(nonce)>10000L){
            return handleNoAuth(response);
        }
        //时间差不超过5分钟
        Long currentTime = System.currentTimeMillis() / 1000;
        final Long FIVE_MINUTES=60*5L;
        if((currentTime-Long.parseLong(timestamp))>=FIVE_MINUTES){
            return handleNoAuth(response);
        }
        //判断密码是否正确
        String secretKey = invokeUser.getSecretKey();
        String serverSign = SignUtils.genSign(body, secretKey);
        if(sign==null||!sign.equals(serverSign)){
            return handleNoAuth(response);
        }
        //请求的模拟接口是否存在
        JsonParser jsonParser = new JsonParser();
        JsonObject jsonObject = jsonParser.parse(body).getAsJsonObject();

        Long id =Long.parseLong(jsonObject.get("id").getAsString());
        InterfaceInfo interfaceInfo=null;
        try {
            interfaceInfo=innerInterfaceInfoService.getInterfaceInfo(id);
            log.info("interfaceInfo:"+interfaceInfo.toString());
        }catch (Exception e){
            log.error("getInterfaceInfo error",e);
        }
        if(interfaceInfo==null){
            return handleNoAuth(response);
        }
        //请求转发，调用模拟接口
        //Mono<Void> filter = chain.filter(exchange);
        //响应日志
        return handleResponse(exchange,chain,interfaceInfo.getId(),invokeUser.getId());
        //log.info("响应："+response.getStatusCode());
        ////调用成功，接口调用次数加1
        //if(response.getStatusCode()==HttpStatus.OK){
        //
        //}else {
        //    return handleInvokeError(response);
        //}
        //log.info("custom global filter");
        //return filter;
    }

    public Mono<Void> handleResponse(ServerWebExchange exchange,GatewayFilterChain chain,long interfaceInfoId,long userId){
        try{
            ServerHttpResponse originalResponse = exchange.getResponse();
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            HttpStatus statusCode = originalResponse.getStatusCode();
            if(statusCode== HttpStatus.OK){
                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse){
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        log.info("body instanceof Flux: {}",(body instanceof Flux));
                        if(body instanceof Flux){
                            Flux<? extends DataBuffer> fluxBody=Flux.from(body);
                            return super.writeWith(fluxBody.map(dataBuffer -> {
                                try {
                                    innerUserInterfaceInfoService.invokeCount(interfaceInfoId,userId);
                                }catch (Exception e){
                                    log.error("invokeCount error",e);
                                }
                                byte[] content=new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(content);
                                DataBufferUtils.release(dataBuffer);
                                StringBuilder sb2=new StringBuilder(200);
                                List<Object> rspArgs = new ArrayList<>();
                                rspArgs.add(originalResponse.getStatusCode());
                                String data=new String(content, StandardCharsets.UTF_8);
                                sb2.append(data);
                                log.info("响应结果"+data);
                                return bufferFactory.wrap(content);
                            }));
                        }else {
                            log.error("<--- {} 响应code异常",getStatusCode());
                        }
                        return super.writeWith(body);
                    }
                };
                return chain.filter(exchange.mutate().response(decoratedResponse).build());
            }
            return chain.filter(exchange);
        }catch (Exception e){
            log.error("网关处理响应异常"+e);
            return chain.filter(exchange);
        }
    }
    public Mono<Void> handleInvokeError(ServerHttpResponse response){
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        return response.setComplete();
    }
    public Mono<Void> handleNoAuth(ServerHttpResponse response){
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.setComplete();
    }
    @Override
    public int getOrder() {
        return -1;
    }
}