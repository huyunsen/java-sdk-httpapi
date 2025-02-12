package com.hanclouds;

import com.hanclouds.exception.HanCloudsClientException;
import com.hanclouds.exception.HanCloudsException;
import com.hanclouds.exception.HanCloudsServerException;
import com.hanclouds.http.AbstractHttpRequest;
import com.hanclouds.http.AbstractHttpResponse;
import com.hanclouds.http.BaseHttpResponse;
import com.hanclouds.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author czl
 * @version 1.0
 * @date 2018/4/8 16:20
 */
public class HanCloudsClient {
    private Logger logger = LoggerFactory.getLogger(HanCloudsClient.class);

    private String gatewayUrl;

    private int reTryCount;

    private long reTryTime;

    private boolean ConnectFlag;

    /**
     * 实体Key
     * 多个key共存情况下按以下优先顺序读取，不会在客户端检测是否有相关权限
     * userKey, productKey, deviceKey, projectKey
     */
    private String userKey;
    private String projectKey;
    private String productKey;
    private String deviceKey;

    private String userAuthKey;
    private String projectAuthKey;
    private String productServiceKey;

    private ScheduledExecutorService scheduler;

    /**
     * Device 对应 token
     */
    private String secretKey;

    /**
     * @param gatewayUrl 参数必须为平台网关路径前缀，例如: http://api.hanclouds.com/api/v1
     */
    public HanCloudsClient(String gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
        this.reTryCount = 0;
        this.reTryTime = 5000;
        this.ConnectFlag = false;
    }

    public void setReTryCount(int reTryCount) {
        this.reTryCount = reTryCount;
    }

    public void setReTryTime(long reTryTime) {
        this.reTryTime = reTryTime;
    }

    public void putUserAuthParams(String userKey, String authKey, String secretKey) {
        if (userKey == null || userKey.isEmpty()
                || authKey == null || authKey.isEmpty()
                || secretKey == null || secretKey.isEmpty()) {
            return;
        }

        this.userKey = userKey;
        this.userAuthKey = authKey;
        this.secretKey = secretKey;
    }

    public void putProjectAuthParams(String projectKey, String authKey, String secretKey) {
        if (StringUtils.isEmpty(projectKey)
                || StringUtils.isEmpty(authKey)
                || StringUtils.isEmpty(secretKey)) {
            return;
        }

        this.projectKey = projectKey;
        this.projectAuthKey = authKey;
        this.secretKey = secretKey;
    }

    public void putProductAuthParams(String productKey, String serviceKey, String secretKey) {
        if (productKey == null || productKey.isEmpty()
                || serviceKey == null || serviceKey.isEmpty()
                || secretKey == null || secretKey.isEmpty()) {
            return;
        }

        this.productKey = productKey;
        this.productServiceKey = serviceKey;
        this.secretKey = secretKey;
    }

    public void putDeviceAuthParams(String deviceKey, String deviceToken) {
        if (deviceKey == null || deviceKey.isEmpty() || deviceToken == null || deviceToken.isEmpty()) {
            return;
        }

        this.deviceKey = deviceKey;
        this.secretKey = deviceToken;
    }

    /**
     * 执行对应request并返回指定Response
     * @param request
     * @param <T>
     * @return
     */
    public <T extends AbstractHttpResponse> T execute(AbstractHttpRequest<T> request) throws HanCloudsException {
        if (request == null) {
            throw new HanCloudsClientException("request is null");
        }

        boolean hadKey = false;
        //放入 key 参数
        if (this.userKey != null && !this.userKey.isEmpty() && this.userAuthKey != null && !this.userAuthKey.isEmpty()) {
            request.putHeader("HC-USER-KEY", this.userKey);
            request.putHeader("HC-USER-AUTH-KEY", this.userAuthKey);
            hadKey = true;
        }

        if (!StringUtils.isEmpty(this.projectKey) && !StringUtils.isEmpty(this.projectAuthKey)) {
            request.putHeader("HC-PROJECT-KEY", this.projectKey);
            request.putHeader("HC-PROJECT-AUTH-KEY", this.projectAuthKey);

            // 处理产品或设备授权的情况
            if(!StringUtils.isEmpty(this.productKey)){
                request.putHeader("HC-PRODUCT-KEY", this.productKey);
            }
            hadKey = true;
        }

        if (!hadKey && this.productKey != null && !this.productKey.isEmpty()
                && this.productServiceKey != null && !this.productServiceKey.isEmpty()) {
            request.putHeader("HC-PRODUCT-KEY", this.productKey);
            request.putHeader("HC-PRODUCT-SERVICE-KEY", this.productServiceKey);
            hadKey = true;
        }

        if (!hadKey && this.deviceKey != null && !this.deviceKey.isEmpty()) {
            request.putHeader("HC-DEVICE-KEY", this.deviceKey);
            hadKey = true;
        }

        if (!hadKey) {
            throw new HanCloudsClientException("no key or auth key or secret key for request");
        }

        request.validate();

        //request签名生成必要参数
        if (this.secretKey == null || this.secretKey.isEmpty()) {
            throw new HanCloudsClientException("no secretKey for request");
        }
        request.signAndPutQueryParams(this.secretKey);

        final HttpURLConnection httpURLConnection = request.getHttpConnection(this.gatewayUrl);
        if (httpURLConnection == null) {
            throw new HanCloudsClientException("request get http connection error");
        }

        final Map<String,String> errorMsgMap = new HashMap<String, String>();
        try {
            httpURLConnection.connect();
            this.ConnectFlag = true;
        }  catch (IOException e) {
            if(this.reTryCount <= 0){
                if(e instanceof SocketTimeoutException){
                    errorMsgMap.put("SocketException",e.getMessage());
                }else{
                    errorMsgMap.put("IOException",e.getMessage());
                }
            }

            if(this.reTryCount > 0 && this.reTryTime > 0) {
                if(null == scheduler){
                    scheduler = Executors.newSingleThreadScheduledExecutor();
                }

                for (int i = 0 ; i < this.reTryCount; i++) {
                    final int j=i;
                    ScheduledFuture<Boolean> schedule = scheduler.schedule(new Callable<Boolean>() {
                        @Override
                        public Boolean call() throws Exception {
                            logger.info("http request is retrying-{}", (j+1));
                            try {
                                httpURLConnection.connect();
                                return true;
                            } catch (Exception retryExeption) {
                                if(retryExeption instanceof SocketTimeoutException){
                                    errorMsgMap.put("SocketException",retryExeption.getMessage());
                                }else{
                                    errorMsgMap.put("IOException",retryExeption.getMessage());
                                }
                                return false;
                            }
                        }
                    }, this.reTryTime, TimeUnit.MILLISECONDS);

                    try {
                        if(schedule.get()){
                            ConnectFlag = true;
                            break;
                        }
                    } catch (InterruptedException interruptedException) {
                        throw new HanCloudsServerException("http request interruptedException"+interruptedException.getMessage());
                    } catch (ExecutionException executionException) {
                        throw new HanCloudsServerException("http request executionException"+executionException.getMessage());
                    }

                }
            }
        }

        if(ConnectFlag){
            errorMsgMap.clear();
        }

        //最终重连还是失败，就抛出异常
        if(!ConnectFlag){
            if(errorMsgMap.containsKey("SocketException")){
                throw new HanCloudsServerException("瀚云rest-gateway地址:"+this.gatewayUrl+" 链接超时,"+errorMsgMap.get("SocketException"));
            }else{
                throw new HanCloudsServerException("瀚云rest-gateway地址:"+this.gatewayUrl+" IO异常,"+errorMsgMap.get("IOException"));
            }
        }

        //获取response
        BaseHttpResponse baseHttpResponse = null;
        try {
            baseHttpResponse = BaseHttpResponse.getResponse(httpURLConnection);
        } catch (IOException e) {
            throw new HanCloudsServerException(e.getMessage());
        } finally {
            httpURLConnection.disconnect();
        }

        if (baseHttpResponse == null) {
            throw new HanCloudsException("system error");
        }

        T resp = null;
        try {
            resp = request.getResponseClass().newInstance();
        } catch (Exception e) {
            throw new HanCloudsException(e.getMessage());
        }

        resp.setBaseHttpResponse(baseHttpResponse);
        resp.parseBaseHttpResponse();

        return resp;
    }


}
