package org.example.seedancegenarate.service;

import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlipayKeyNormalizationTest {

    // 标准 PKCS#8 格式私钥（包含 -----BEGIN PRIVATE KEY----- 与换行）
    private static final String PKCS8_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCUAwkKIiwDyhCk
            Tw5jID0zqy2b89+vpsFmT71JVplnKit1ukhaYw1PjXlZeI5pXVz7+KJJZE5ANdDt
            Fx9IAAZWHwLAWvxmzD2d7ZebQUYwSTb0/H/nvJ6WwquEnEPgoN7PWUEnxZgUDdoc
            KzwED7GbWEWbdgkWpSZhudINeJsd2bz8SZzYyTQ5OkUeFx/UqUNU8F4xUB7Fuhqj
            JZ1P6nWzWhPMF/+oQfFhdCS3ZABhGG/4Qls7HL2912rOUgl0REmeJtzuX0NiZTuP
            wiuktm6ay0viz3WwSlLpuGzUHROPfggt7zeILR6oBMxjUmqkmkv7jh66uJQAEGac
            e8/aUinXAgMBAAECggEAN/t968tJApXm/X65XlzPST+xnI79SB62f9AhNCABHbgh
            gAHZY/abDj/gairjEo8xoExGdaPuxPKV91cLidwYiXJpRAAl/2u40ocPFLX3qkPG
            +ZqXdjstcLBo11uTgN7X521G65gdEVi76nOf25lj//G/QjG+9kW44rW58UZwxTUG
            XfOTN9mco4sOfpya/Slov5FM1exQYnkTWtsH4Nuea0B3JSS2grFyKpKUghvNFglf
            lqnbcR8mBk2+5vAkBLk1gvrXkmqnjSXVaNfOl5n2zLIkOT14CPAo1Rf+OKfSF1cE
            t5NQH8stQQ6PKCtHkSn9X8uzIvXgTr9jGZY45/UIoQKBgQDQuBOxGOPzsTpDil2r
            KrnuE48I1HZ1a2ri0p6xps35BzV9klVPBgjmSE201RT1DfaNLZQ4dYA7aiASHtFn
            1nbBNA6hM/ksSSLz9Gu1v/7JrfmyKfFGLmCj15aWTHf83CdDALdD9sBVYsMySfwm
            bHfz1q5jIxpmNU9rkPAnba6tFQKBgQC1inWb/D3GZ/JFJLug7iJU355tXzv/X5UD
            eaB2oS9U/CGWdPWrpCo0Pj01YapOmao8BYhQrZ/nxSgccrQdX4kZk7DEFJj21lz7
            J7KWngeYoOF0QpQ6GW/h/lYFHwatM+Ka46+W1ey2ox1+mXTWvgvN0ZTOkUl1gBFk
            iqTKRryuOwKBgHxeI0W0qifMZAZYzWWv6Ohm+STuzMM8xYzwqaT72uHXnNT9PB4B
            X44waTUmfv1iW5ZWIWrfARDmNMP3Xxn7nfmT1l/SA1iTH+Ozsfazt9Ne+lliSqVh
            /y3BybY3TIv8dtyi8ZDq2EJIvj0Z/si6e2Ntea4S/akHfRKAY8lqvtndAoGAaTcB
            KNGNgBM4j6hVclTx4tzjtiHu5Pghpiz0uix7ATdLxCavR7ZSm4rC8NBU408eIcFX
            GuM1/R8AO1SjXS7Eh+VYGpyRduYQZ6O/VpnqbyQ25qm7vNyHQqSkeD1eIj4jBIAX
            gUUk90kcTAZmeBsXPkdMhlggChCag/nyviU3L4cCgYAd58JfrD0UDf31ti0qZNaA
            uvYnNUa8TMZuVxlJ0H1xIN6Z0u4sRU/z4PMFqbYN7MuGloNZinbqVoaBR+xAD09X
            R9eR8bxWV+CJYerhKIBwmzs6i7D1dMlAOLpvMAbbMPetDucENRI2NwW5BQagUmwo
            9ds9SxGo3di83cwe66pXKg==
            -----END PRIVATE KEY-----
            """;

    @Test
    void testNormalizePkcs8KeyAndGeneratePrivate() throws Exception {
        String normalized = AlipayPaymentService.normalizeKey(PKCS8_KEY);
        byte[] keyBytes = Base64.getDecoder().decode(normalized);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);
        assertNotNull(privateKey);
        assertTrue(privateKey.getAlgorithm().equalsIgnoreCase("RSA"));
    }
}
