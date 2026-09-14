package org.example.seedancegenarate.agent.generation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.example.seedancegenarate.agent.generation.AgentVideoPromptPreparation.Scene;
import org.example.seedancegenarate.agent.skill.StoryboardSceneDetails;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static org.example.seedancegenarate.agent.generation.VideoPreparationException.ValidationRule.*;

/** Pure, version-bound codec for reviewed packaged guidance. Unknown guidance stays on v3. */
public final class StructuredVideoPromptProtocol {
    private static final int MAX_PROMPT_CHARS=4000;
    private static final String DEFAULT="prompts/default.md", FL="prompts/minimax-h3-fl2va-hd.md", T2V="prompts/minimax-h3-t2v-hd.md";
    private static final List<String> THREE=List.of("integrated_multimodal_description","overall_soundscape","non_diegetic_music");
    private static final List<String> SIX=List.of("subject_definitions","summary","retention_analysis","detailed_description","overall_soundscape","non_diegetic_music");
    private static final String FIRST_PREFIX="For the target video, at 0.00 seconds into the target video, <Picture 1> (from [Shot 1]) is fully referenced.";
    // Fingerprints cover the whole reviewed guide, not just its headings. Updates require a new review.
    private static final Map<String,String> GUIDES=Map.ofEntries(
            Map.entry(DEFAULT,"959dc726da2c2e56ac2fa23d646c6ebef59eaa453496f063f443a43ec8b6f7ad"),
            Map.entry("prompts/minimax-h3.md","ebb3391d830c77ab0c595d4e53dc4d8efb00f3de0fd62efd57237391843c7f85"),
            Map.entry("prompts/minimax-h3-accel.md","ebb3391d830c77ab0c595d4e53dc4d8efb00f3de0fd62efd57237391843c7f85"),
            Map.entry("prompts/minimax-h3-hd.md","3a03f78b5dfe5ffaa84986b16155fc68e67ed8a107c20d140cc06ba0a532da6f"),
            Map.entry("prompts/minimax-h3-4step.md","a6bbd5aa5c5bb5e0d12cd984ae96f1aae808e0e67d9ac59d4c9b56c87836a661"),
            Map.entry("prompts/minimax-h3-opt.md","b5b4344b95c5199a8c3cc27cc7de1ccd31657a5bc1abdbc9f3c2b73a49f517e4"),
            Map.entry("prompts/minimaxh3-hd-fast.md","c2b38651113236d0f1d01566a74e456bc240e2fab028cb6fc7d80bf7bcff5c94"),
            Map.entry(T2V,"5ce2299b5a88a231380a4be181c399106fa4cfc89db7782eceadac7a12daa5cb"),
            Map.entry(FL,"ed56b0022eec6ee4d9297d72b34c3a214ffd4ef321b4f36d64eab40ae2d2b623"));
    private static final Pattern MEDIA_TAG=Pattern.compile("<\\s*(Picture|Video|Audio)\\s+(\\d+)\\s*>",Pattern.CASE_INSENSITIVE);
    private StructuredVideoPromptProtocol() {}

    public static boolean supports(Scene scene) {
        if(scene==null||scene.templateId()==null||scene.guide()==null||scene.request()==null||!scene.request().isObject())return false;
        String expected=GUIDES.get(scene.templateId());if(expected==null)return false;
        var parameters=scene.request().path("parameters");
        if(!parameters.isObject())return false;
        int images=parameters.has("referenceImage")?1:0;
        if(images==1) {
            var ref=parameters.path("referenceImage");
            if(!ref.isObject()||ref.size()!=2||!ref.path("artifactId").isTextual()||ref.path("artifactId").asText().isBlank()
                    ||!ref.path("version").isIntegralNumber()||!ref.path("version").canConvertToInt()||ref.path("version").asInt()<1)return false;
            String mode=parameters.path("referenceMode").asText();
            if(!Set.of("REFERENCE_IMAGE","FIRST_FRAME").contains(mode)||T2V.equals(scene.templateId()))return false;
            if(FL.equals(scene.templateId())&&!"FIRST_FRAME".equals(mode))return false;
        } else if(parameters.has("referenceMode"))return false;
        // Current Agent parameters cannot identify two frames or a last frame. Never infer them from prose.
        if(parameters.has("referenceImages")||parameters.has("referenceVideo")||parameters.has("referenceAudio"))return false;
        String reviewed=scene.guide().trim().replace("用户接入了 "+images+" 张参考图","用户接入了 {imageCount} 张参考图")
                .replace("、0 段参考视频、0 段参考音频","、{videoCount} 段参考视频、{audioCount} 段参考音频");
        try {
            return expected.equals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(reviewed.getBytes(StandardCharsets.UTF_8))));
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }

    private static List<String> headings(Scene scene) {
        return DEFAULT.equals(scene.templateId())?List.of("description"):
                FL.equals(scene.templateId())||T2V.equals(scene.templateId())?THREE:SIX;
    }
    private static String prefix(Scene scene) {
        return FL.equals(scene.templateId())&&scene.request().path("parameters").has("referenceImage")?FIRST_PREFIX+"\n\n":"";
    }
    private static void requireSupported(Scene scene) {
        if(!supports(scene))throw failure(VideoPreparationException.Reason.BATCH,scene);
    }
    private static void requireLimit(Scene scene,int limit) {
        requireSupported(scene);
        if(limit<1||limit>MAX_PROMPT_CHARS)throw failure(VideoPreparationException.Reason.PROMPT_LENGTH,scene);
    }
    private static VideoPreparationException failure(VideoPreparationException.Reason reason,Scene scene) {
        var failure=new VideoPreparationException(reason);
        return scene==null?failure:failure.atScene(scene.ordinal()).withSource(scene.source());
    }
    private static VideoPreparationException invalid(VideoPreparationException.ValidationRule rule,Scene scene,String path) {
        return VideoPreparationException.invalid(rule,path).atScene(scene.ordinal()).withSource(scene.source());
    }
    public static Map<String,Integer> limits(List<Scene> scenes) {
        if(scenes==null||scenes.isEmpty()||scenes.size()>12)throw failure(VideoPreparationException.Reason.BATCH,null);
        var keys=new HashSet<String>();
        for(var scene:scenes) {
            requireSupported(scene);
            if(scene.key()==null||scene.key().isBlank()||scene.ordinal()<1||!keys.add(scene.key()))throw failure(VideoPreparationException.Reason.BATCH,scene);
        }
        var ordered=scenes.stream().sorted(Comparator.comparingInt(Scene::ordinal).thenComparing(Scene::key)).toList();
        int n=ordered.size(),used=0,totalCap=0;int[] limits=new int[n];long[] weights=new long[n];
        for(int i=0;i<n;i++) {
            var scene=ordered.get(i);limits[i]=minimum(scene);weights[i]=complexity(scene);used+=limits[i];totalCap+=MAX_PROMPT_CHARS;
            if(limits[i]>MAX_PROMPT_CHARS||used>16000)throw failure(VideoPreparationException.Reason.PROMPT_LENGTH,scene);
        }
        int remaining=Math.min(16000,totalCap)-used;
        while(remaining>0) {
            long totalWeight=0;for(int i=0;i<n;i++)if(limits[i]<MAX_PROMPT_CHARS)totalWeight+=weights[i];
            int round=remaining;
            for(int i=0;i<n;i++)if(limits[i]<MAX_PROMPT_CHARS) {
                int share=(int)Math.min(MAX_PROMPT_CHARS-limits[i],round*weights[i]/totalWeight);
                limits[i]+=share;remaining-=share;
            }
            // Only fractional remainders remain; ties use the stable source ordinal/key ordering.
            if(remaining==round)for(int i=0;i<n&&remaining>0;i++)if(limits[i]<MAX_PROMPT_CHARS){limits[i]++;remaining--;}
        }
        var result=new LinkedHashMap<String,Integer>();for(int i=0;i<n;i++)result.put(ordered.get(i).key(),limits[i]);
        return Collections.unmodifiableMap(result);
    }

    private static int minimum(Scene scene) {
        if(scene.request().toString().length()>24000)throw failure(VideoPreparationException.Reason.PROMPT_LENGTH,scene);
        var board=scene.request().path("storyboardScene");
        try {StoryboardSceneDetails.spokenLines(board);}
        catch(org.example.seedancegenarate.exception.BusinessException|IllegalArgumentException invalidSource) {
            throw invalid(SOURCE_SPEECH_INVALID,scene,"$.storyboardScene.sound");
        }
        boolean plain=DEFAULT.equals(scene.templateId());int required=plain?64:384+prefix(scene).length();
        if(!plain)required+=headings(scene).stream().mapToInt(h->h.length()+4).sum()-2;
        var speakers=new LinkedHashMap<String,String>();
        for(var line:board.path("sound").path("dialogue")) {
            String speaker=line.path("speaker").asText(),text=line.path("text").asText();
            String id=speakers.computeIfAbsent(speaker,key->"S"+(speakers.size()+1));
            required+=(plain?speaker+":"+text:"The character ("+id+") says: <d>[Chinese] "+text+"</d>").length()+1;
        }
        if(!plain)required+=speakers.keySet().stream().mapToInt(name->name.length()+32).sum();
        String narration=board.path("sound").has("narration")?board.path("sound").path("narration").asText():board.path("narration").asText("");
        for(String line:narration.lines().filter(v->!v.isBlank()).toList())
            required+=(plain?line:"The narrator says in an off-screen voiceover: <d>[Chinese] "+line+"</d> while lips remain completely closed.").length()+1;
        return required;
    }
    private static long complexity(Scene scene) {
        var board=scene.request().path("storyboardScene");long weight=1;
        for(String key:List.of("visual","narration","shot","sound","characters"))weight+=textLength(board.path(key));
        return weight+textLength(scene.request().path("parameters").path("visualStyle"));
    }
    private static long textLength(JsonNode node) {
        if(node.isTextual())return node.textValue().length();
        long result=0;for(var child:node)result+=textLength(child);return result;
    }

    public static String policy(Scene scene,int limit) {
        requireLimit(scene,limit);
        String fields=headings(scene).stream().map(h->"\""+h+"\":\"正文\"").collect(java.util.stream.Collectors.joining(","));
        String shape="只返回严格JSON：{\"items\":[{\"key\":\"输入原key\",\"sections\":{"+fields+"}}]}。"
                +"只处理当前一幕，字段必须且只能为"+String.join(",",headings(scene))+"，全部为非空字符串。"
                +"不返回prompt、标题行、指令前缀、代码围栏、解释或重复键；标题与前缀由系统组装。"
                +"组装后本幕最多"+limit+"字符（含标题、空行与前缀），全批正文最多16000，完整JSON最多24000字符。"
                +"保留已确认身份、服装、风格、动作、构图、时长与画幅，使用本幕parameters/storyboardScene，不改模型、不混入其他幕或新增剧情。"
                +"只按parameters.referenceImage与referenceMode使用实际参考；characters.referenceImage仅是资料，不代表已附图。不虚构图片/视频/音频参考。";
        if(DEFAULT.equals(scene.templateId()))return shape+"description为简洁连续中文，按本幕有效额度写清主体场景、动作过程、镜头、光影与风格，不得为缩短正文删去完整对白。"
                +"保留对白说话人:原文与完整旁白，sound.narration和narration相同只写一次，图生保持身份与构图。";
        String writing="正文用English；仅对白、歌词及画面内可见文字保留原文语言与标点，不翻译或改写。"
                +"以[Shot 1]确立风格和构图且不带时间戳；后续[Shot N]按递增时间戳切镜并在实际时长内收束。"
                +"逐镜写动作、主体外观位置、环境光线、运镜类型/幅度/速度及同步声音；只在明确要求时溶解或淡入淡出。"
                +"严格沿用speakerBindings稳定编号，首次交代身份/音色/语速，不给无声角色占号。"
                +"对白写The character (S1) says: <d>[Chinese] 原文</d>（可shouts，编号必须对应输入，Language与原文一致）；角色名不进d标签。"
                +"旁白写says in an off-screen voiceover并紧随lips remain completely closed；旁白只写一次。"
                +"跨切镜语音用<scenetrans>保持连续，结尾截断用<cutoff>。画面可见文字加英文双引号并保留原文。"
                +"overall_soundscape概括环境/动作/非语言人声，不重复对白或配乐，明确全片静音才N/A；"
                +"non_diegetic_music仅写观众听到的乐器、节奏与强弱，无配乐N/A；角色听到的音乐写正文。";
        if(headings(scene).equals(SIX))writing+="subject_definitions定义实际引用标签；summary用准确任务类型前缀；retention_analysis逐标签标注保留方式和出现镜头。无引用填写N/A，不虚构引用关系。";
        if(FL.equals(scene.templateId()))writing+=prefix(scene).isEmpty()?"当前无图，按T2VA写三段，不声明Picture。":
                "当前仅FIRST_FRAME：Picture 1是0.00秒的首帧。先保持首帧身份/构图，再描述连续发展；不声明尾帧或Picture 2。";
        return shape+writing;
    }
    public static String parse(ObjectMapper json,String raw,Scene scene,int limit) {
        requireLimit(scene,limit);
        if(raw==null||raw.isBlank())throw invalid(JSON_INVALID,scene,"$");
        if(raw.length()>24000)throw invalid(PROMPT_TOO_LONG,scene,"$");
        JsonNode root;
        try {
            root=json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);
        } catch(com.fasterxml.jackson.core.JsonProcessingException malformed) {throw invalid(JSON_INVALID,scene,"$");}
        if(root==null||!root.isObject()||root.size()!=1||!root.path("items").isArray())throw invalid(ROOT_SHAPE,scene,"$");
        if(root.path("items").size()!=1)throw invalid(SCENE_KEY_MISMATCH,scene,"$.items");
        var item=root.path("items").get(0);
        if(!item.isObject()||item.size()!=2||!item.path("key").isTextual()||!item.has("sections"))
            throw invalid(STRUCTURED_ITEM_SHAPE,scene,"$.items[0]");
        if(!item.path("key").textValue().equals(scene.key()))throw invalid(SCENE_KEY_MISMATCH,scene,"$.items[0].key");
        var sections=item.path("sections");var headings=headings(scene);
        if(!sections.isObject()||sections.size()!=headings.size())throw invalid(STRUCTURED_SECTIONS,scene,"$.items[0].sections");
        var result=new StringBuilder(prefix(scene));boolean plain=DEFAULT.equals(scene.templateId());
        for(int i=0;i<headings.size();i++) {
            String heading=headings.get(i),path="$.items[0].sections."+heading;var value=sections.path(heading);
            if(!value.isTextual()||value.textValue().isBlank())throw invalid(STRUCTURED_SECTIONS,scene,path);
            String body=value.textValue();
            if(!plain)for(String line:body.lines().toList())for(String known:headings)
                if(line.stripLeading().startsWith(known+":"))throw invalid(STRUCTURED_SECTIONS,scene,path);
            // Free prose may quote these tags as dialogue or visible text; it is not a media-input declaration.
            if(heading.equals("subject_definitions")||heading.equals("retention_analysis"))validateReferences(scene,body,path);
            if(i>0)result.append("\n\n");
            if(!plain)result.append(heading).append(":\n");
            result.append(body);
        }
        String rendered=result.toString().trim();
        if(rendered.length()>limit)throw invalid(PROMPT_TOO_LONG,scene,"$.items[0].sections");
        return rendered;
    }
    private static void validateReferences(Scene scene,String body,String path) {
        var references=MEDIA_TAG.matcher(body);
        while(references.find()) {
            if(!references.group(1).equalsIgnoreCase("Picture")||!scene.request().path("parameters").has("referenceImage")
                    ||!references.group(2).equals("1"))throw invalid(STRUCTURED_SECTIONS,scene,path);
        }
    }
}
