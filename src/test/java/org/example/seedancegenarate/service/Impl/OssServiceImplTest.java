package org.example.seedancegenarate.service.Impl;

import com.aliyun.oss.OSS;
import org.example.seedancegenarate.config.OssConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class OssServiceImplTest {

    @Test
    void resolvesBareDomainAsHttpsUrl() {
        OssConfig config = config("hszs-generate-api.oss-cn-beijing.aliyuncs.com");

        assertEquals("https://hszs-generate-api.oss-cn-beijing.aliyuncs.com",
                new OssServiceImpl(config, mock(OSS.class)).resolveBaseDomain());
    }

    @Test
    void preservesConfiguredSchemeAndStripsTrailingSlash() {
        OssConfig config = config("http://oss.example.com/");

        assertEquals("http://oss.example.com", new OssServiceImpl(config, mock(OSS.class)).resolveBaseDomain());
    }

    @Test
    void fallsBackToBucketEndpointWhenDomainIsBlank() {
        OssConfig config = config(" ");
        config.setBucketName("bucket");
        config.setEndpoint("https://oss-cn-beijing.aliyuncs.com");

        assertEquals("https://bucket.oss-cn-beijing.aliyuncs.com",
                new OssServiceImpl(config, mock(OSS.class)).resolveBaseDomain());
    }

    private OssConfig config(String domain) {
        OssConfig config = new OssConfig();
        config.setDomain(domain);
        return config;
    }
}
