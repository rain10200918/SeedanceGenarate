package org.example.seedancegenarate.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiModelView;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.service.ModelAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiModelControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ModelAccessService access;

    @BeforeEach
    void setUp() {
        UserContext.clear();
        asUser("USER");
        access = mock(ModelAccessService.class);
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    // 【测什么】真实 GET 的完整 JSON 保留旧字段并逐枚举映射四能力，参考容量使用不同非零值。
    // 【怎么算红】漏字段、交换 videoMax/audioMax、写死图片模式或改旧字段/离散时长，完整树比较必红。
    @ParameterizedTest
    @EnumSource(ModelSpec.ImageInputMode.class)
    void serializesCapabilitiesFromModelSpec(ModelSpec.ImageInputMode mode) throws Exception {
        ModelSpec spec = new ModelSpec("provider", "model", "模型", true, 1, 3,
                List.of("16:9", "1:1"), 5, 10, List.of(5, 8, 10), OutputType.VIDEO,
                List.of(1.0, 2.0), 2, 4, true, mode);
        when(access.isOpen("model")).thenReturn(true);

        JsonNode actual = read(mvc(engine("provider", spec)));

        JsonNode expected = mapper.readTree("""
                [{"model":"model","label":"模型","provider":"provider","outputType":"VIDEO",
                  "needImages":true,"imageMin":1,"imageMax":3,"ratios":["16:9","1:1"],
                  "durations":[5,8,10],"megapixels":[1.0,2.0],"open":true,
                  "videoMax":2,"audioMax":4,"needImageOrVideo":true,"imageInputMode":"%s","resolutions":[],"defaultResolution":null}]
                """.formatted(mode.name()));
        assertEquals(expected, actual);
    }

    // 【测什么】无参考能力的图片/音频/视频模型保留零值和 false，空能力列表不伪造可选项。
    // 【怎么算红】丢弃零值/false、把产物类型写死 VIDEO 或凭空生成时长/比例，完整树比较必红。
    @ParameterizedTest
    @EnumSource(OutputType.class)
    void serializesZeroCapabilitiesAndEmptyOptions(OutputType outputType) throws Exception {
        ModelSpec spec = new ModelSpec("provider", "zero", "零能力", false, 0, 0,
                List.of(), 0, 0, List.of(), outputType, List.of());
        when(access.isOpen("zero")).thenReturn(true);
        assertEquals(mapper.readTree("""
                [{"model":"zero","label":"零能力","provider":"provider","outputType":"%s",
                  "needImages":false,"imageMin":0,"imageMax":0,"ratios":[],"durations":[],
                  "megapixels":[],"open":true,"videoMax":0,"audioMax":0,
                  "needImageOrVideo":false,"imageInputMode":"NONE","resolutions":[],"defaultResolution":null}]
                """.formatted(outputType.name())), read(mvc(engine("provider", spec))));
    }

    // 【测什么】连续区间两端都输出，包括只有一个合法时长的区间。
    // 【怎么算红】将 rangeClosed 改成 range 或只返回区间端点，期望的完整时长列表必红。
    @ParameterizedTest
    @ValueSource(ints = {5, 7})
    void expandsInclusiveDurationRange(int max) throws Exception {
        ModelSpec spec = new ModelSpec("provider", "range", "range", false, 0, 0,
                List.of(), 5, max, List.of());
        when(access.isOpen("range")).thenReturn(true);
        assertEquals(mapper.valueToTree(max == 5 ? List.of(5) : List.of(5, 6, 7)),
                read(mvc(engine("provider", spec))).get(0).get("durations"));
    }

    // 【测什么】旧 11 参数 Java 构造器仍可调用，序列化的所有旧字段及新增默认值完整保留。
    // 【怎么算红】移除兼容构造器将编译失败；图片模式默认值或旧字段改变则完整 JSON 比较红。
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void legacyConstructorRemainsCompatible(int imageMax) throws Exception {
        ApiModelView view = new ApiModelView("legacy", "旧模型", "provider", "IMAGE", false,
                0, imageMax, List.of("1:1"), List.of(), List.of(1.0), false);
        assertEquals(mapper.readTree("""
                {"model":"legacy","label":"旧模型","provider":"provider","outputType":"IMAGE",
                 "needImages":false,"imageMin":0,"imageMax":%d,"ratios":["1:1"],"durations":[],
                 "megapixels":[1.0],"open":false,"videoMax":0,"audioMax":0,
                 "needImageOrVideo":false,"imageInputMode":"%s","resolutions":[],"defaultResolution":null}
                """.formatted(imageMax, imageMax == 0 ? "NONE" : "UNSPECIFIED")),
                mapper.readTree(mapper.writeValueAsString(view)));
    }
    // 【测什么】普通属主只看到开放模型，跨提供方与同提供方模型均按原规则排序。
    // 【怎么算红】取消开放过滤或 provider/model 任一排序维度，模型顺序或数量断言必红。
    @Test
    void filtersClosedModelsAndSortsForOrdinaryOwner() throws Exception {
        when(access.isOpen("closed")).thenReturn(false);
        when(access.isOpen("a")).thenReturn(true);
        when(access.isOpen("z")).thenReturn(true);
        when(access.isOpen("b")).thenReturn(true);
        JsonNode result = read(mvc(engine("z-provider", basic("b")),
                engine("a-provider", basic("z"), basic("closed"), basic("a"))));
        assertEquals(3, result.size());
        assertEquals(List.of("a", "z", "b"), List.of(result.get(0).get("model").textValue(),
                result.get(1).get("model").textValue(), result.get(2).get("model").textValue()));
        for (JsonNode item : result) assertEquals(mapper.valueToTree(true), item.get("open"));
    }

    // 【测什么】管理员能看到关闭模型及其真实能力，open 仍为 false。
    // 【怎么算红】管理员也过滤关闭模型或伪造 open=true，数量/开放标记断言必红。
    @Test
    void adminSeesClosedModelWithActualCapabilities() throws Exception {
        asUser("ADMIN");
        ModelSpec spec = new ModelSpec("provider", "closed", "closed", false, 0, 2,
                List.of(), 5, 5, List.of(), OutputType.VIDEO, List.of(), 3, 1, true,
                ModelSpec.ImageInputMode.FIRST_LAST_FRAME);
        when(access.isOpen("closed")).thenReturn(false);
        JsonNode result = read(mvc(engine("provider", spec)));
        assertEquals(1, result.size());
        JsonNode item = result.get(0);
        assertEquals(mapper.valueToTree(false), item.get("open"));
        assertEquals(mapper.valueToTree(3), item.get("videoMax"));
        assertEquals(mapper.valueToTree(1), item.get("audioMax"));
        assertEquals(mapper.valueToTree(true), item.get("needImageOrVideo"));
        assertEquals("FIRST_LAST_FRAME", item.get("imageInputMode").textValue());
    }

    // 【测什么】空注册表和全部关闭模型均返回 JSON 空数组。
    // 【怎么算红】返回 null、泄露关闭模型或给空结果添加包装，数组比较必红。
    @Test
    void emptyRegistryAndAllClosedReturnEmptyArrays() throws Exception {
        assertEquals(mapper.readTree("[]"), read(mvc()));
        when(access.isOpen("closed")).thenReturn(false);
        assertEquals(mapper.readTree("[]"), read(mvc(engine("provider", basic("closed")))));
    }

    // 【测什么】同一 Controller 重复 GET 稳定，随后开关/属主变化会重新读取，不缓存管理员可见结果。
    // 【怎么算红】缓存列表或管理员身份，会在关模型、切管理员或切回普通属主时返回旧结果。
    @Test
    void repeatedGetReevaluatesAccessAndOwner() throws Exception {
        MockMvc mvc = mvc(engine("provider", basic("model")));
        when(access.isOpen("model")).thenReturn(true);
        JsonNode first = read(mvc);
        assertEquals(1, first.size());
        assertEquals(first, read(mvc));
        when(access.isOpen("model")).thenReturn(false);
        assertEquals(mapper.readTree("[]"), read(mvc));
        asUser("ADMIN");
        JsonNode admin = read(mvc);
        assertEquals(1, admin.size());
        assertEquals(mapper.valueToTree(false), admin.get(0).get("open"));
        asUser("USER");
        assertEquals(mapper.readTree("[]"), read(mvc));
        verify(access, times(5)).isOpen("model");
    }

    // 【测什么】开放状态查询失败不会吞掉异常并返回成功空列表。
    // 【怎么算红】捕获服务异常并返回空列表或默认开放时，预期的原始失败原因断言必红。
    @Test
    void accessFailureIsNotReportedAsSuccessfulEmptyList() {
        IllegalStateException failure = new IllegalStateException("access unavailable");
        when(access.isOpen("model")).thenThrow(failure);
        MockMvc mvc = mvc(engine("provider", basic("model")));
        ServletException thrown = assertThrows(ServletException.class,
                () -> mvc.perform(get("/api/v1/models")));
        assertSame(failure, thrown.getCause());
    }

    private ModelSpec basic(String model) {
        return new ModelSpec("provider", model, model, false, 0, 0,
                List.of(), 5, 5, List.of());
    }

    private VideoEngine engine(String provider, ModelSpec... specs) {
        VideoEngine engine = mock(VideoEngine.class);
        when(engine.provider()).thenReturn(provider);
        when(engine.models()).thenReturn(List.of(specs));
        return engine;
    }

    private MockMvc mvc(VideoEngine... engines) {
        return MockMvcBuilders.standaloneSetup(new ApiModelController(
                        new VideoEngineRegistry(List.of(engines)), access))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private JsonNode read(MockMvc mvc) throws Exception {
        byte[] body = mvc.perform(get("/api/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsByteArray();
        return mapper.readTree(body);
    }

    private void asUser(String role) {
        AppUser user = new AppUser();
        user.setId(42L);
        user.setRole(role);
        UserContext.setUser(user);
    }
}
