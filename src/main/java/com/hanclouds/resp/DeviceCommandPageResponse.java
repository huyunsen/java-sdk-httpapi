package com.hanclouds.resp;

import com.hanclouds.http.AbstractHttpResponse;
import com.hanclouds.http.BaseHttpResponse;
import com.hanclouds.model.DeviceCommandDTO;
import com.hanclouds.model.PageResponse;
import com.hanclouds.util.FastJsonTools;

/**
 * @author czl
 * @version 1.0
 * @date 2018/4/30 10:05
 */
public class DeviceCommandPageResponse extends AbstractHttpResponse {

    private PageResponse<DeviceCommandDTO> pageResponse;

    public PageResponse<DeviceCommandDTO> getPageResponse() {
        return pageResponse;
    }

    public void setPageResponse(PageResponse<DeviceCommandDTO> pageResponse) {
        this.pageResponse = pageResponse;
    }

    @Override
    public void parseBaseHttpResponse() {
        BaseHttpResponse baseHttpResponse = this.baseHttpResponse;
        if (baseHttpResponse == null || baseHttpResponse.getBodyContent() == null) {
            return;
        }

        this.pageResponse = FastJsonTools.getPage(new String(baseHttpResponse.getBodyContent()), DeviceCommandDTO.class);
    }

}
