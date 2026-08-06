package org.example.seedancegenarate.service.Impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.SeedanceConfig;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.SeedanceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeedanceServiceImpl implements SeedanceService {

    private final SeedanceConfig seedanceConfig;
    private final ObjectMapper objectMapper;

    @Override
    public String generate(
            List<String> imagePaths,
            String prompt,
            Integer duration,
            String ratio,
            String model
    ) throws Exception {

        log.info("开始调用Seedance生成视频");

        List<Map<String,Object>> content = new ArrayList<>();

        // 提示词
        Map<String,Object> text = new HashMap<>();
        text.put("type","text");
        text.put("text",prompt);
        content.add(text);


        // 参考图片
        for(String imagePath:imagePaths){

            Map<String,Object> image = new HashMap<>();

            image.put(
                    "type",
                    "image_url"
            );


            Map<String,String> imageUrl =
                    new HashMap<>();

            imageUrl.put(
                    "url",
                    imagePath
            );


            image.put(
                    "image_url",
                    imageUrl
            );


            image.put(
                    "role",
                    "reference_image"
            );


            content.add(image);
        }


        Map<String,Object> body =
                new HashMap<>();

        // 引擎已把模型标识解析成方舟 API 模型名；为空兜底用配置默认（单模型模式）
        body.put(
                "model",
                model == null ? seedanceConfig.getModel() : model
        );

        body.put(
                "content",
                content
        );

        body.put(
                "ratio",
                ratio
        );

        body.put(
                "duration",
                duration
        );

        body.put(
                "generate_audio",
                true
        );

        body.put(
                "watermark",
                false
        );

        String json = objectMapper.writeValueAsString(body);

        log.info("请求参数:{}",json);

        HttpResponse response =
                HttpRequest.post(seedanceConfig.getUrl())
                        .header(
                                "Authorization",
                                "Bearer " + seedanceConfig.getApiKey()
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .body(json)
                        .execute();



        if(!response.isOk()){

            throw new RuntimeException(
                    "Seedance调用失败:"
                            + response.body()
            );
        }

        JsonNode node =
                objectMapper.readTree(
                        response.body()
                );
        log.info("Seedance返回:{}", node);
        log.info(
                "Seedance返回:{}",
                response.body()
        );


        if(node.get("id")==null){

            throw new RuntimeException(
                    "没有返回任务ID"
            );
        }


        return node
                .get("id")
                .asText();
    }

    @Override
    public Object getTask(String taskId) throws Exception {
        log.info("查询Seedance任务:{}", taskId);
        String url =
                seedanceConfig.getUrl()
                        + "/"
                        + taskId;
        HttpResponse response =
                HttpRequest.get(url)
                        .header(
                                "Authorization",
                                "Bearer " + seedanceConfig.getApiKey()
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .execute();
        if(!response.isOk()){
            throw new RuntimeException(
                    "查询Seedance任务失败:"
                            + response.body()
            );
        }
        JsonNode node =
                objectMapper.readTree(
                        response.body()
                );
        log.info(
                "任务状态:{}",
                node.toString()
        );
        return node;
    }
}