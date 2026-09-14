package org.example.seedancegenarate.agent.generation;

import org.example.seedancegenarate.exception.BusinessException;
import java.util.List;

/** Safe, finite preparation reasons; never forwards provider or model response text. */
public final class VideoPreparationException extends BusinessException {
    /** Only platform-authored rules may reach the model's repair context. */
    public enum ValidationRule {
        JSON_INVALID("返回一个完整 JSON 对象，不要附加说明、重复键或额外对象。"),
        ROOT_SHAPE("根对象必须且只能包含 items 数组，数组覆盖本次输入的全部场景。"),
        ITEM_SHAPE("每个 items 元素必须且只能包含字符串 key 与 prompt。"),
        STRUCTURED_ITEM_SHAPE("每项只能包含字符串key与sections对象，不返回prompt。"),
        STRUCTURED_SECTIONS("sections必须且只能包含本轮列出的字段，每个值为非空正文字符串，标题由系统组装。"),
        SCENE_KEY_MISMATCH("逐项使用本次输入的原始 key，不新增、重复、遗漏或合并场景。"),
        PROMPT_EMPTY("每个 prompt 必须包含完整非空正文。"),
        PROMPT_TOO_LONG("按本轮明确的单幕、总正文与 JSON 长度上限精简描述，保留全部台词。"),
        TEMPLATE_SECTION_MISSING("按当前模型模板顺序保留所有段落标题，每个标题出现一次并填写正文。"),
        TEMPLATE_SECTION_EMPTY("模板每个段落必须有正文，无相应声音要求时填写 N/A。"),
        DIALOGUE_MISSING("逐条保留旁白及对白完整原文，不能漏字或改字。严格按输入speakerBindings指定说话人编号，写成The character (对应编号) says: <d>[Chinese] 原文台词</d>，喊叫可用shouts；不得自行编号或交换角色。也可用真实角色名:原文台词；角色名不能放进d标签。"),
        SOURCE_SPEECH_INVALID("分镜中的声音资料无效，请检查原始分镜。");
        private final String hint;
        ValidationRule(String hint){this.hint=hint;}
        public String repairHint(){return hint;}
        public static String trustedHint(String supplied) {
            if(supplied==null)return null;
            for(var rule:values())if(rule.hint.equals(supplied))return supplied;
            return null;
        }
    }
    public enum Reason {
        MODEL("VIDEO_MODEL_UNAVAILABLE","所选视频模型当前未开放或不支持这类输入，请调整生成方案。"),
        DURATION("VIDEO_DURATION_UNSUPPORTED","视频时长不在所选模型支持范围，请调整分镜时长或选择合适模型。"),
        TOTAL_DURATION("VIDEO_TOTAL_DURATION_MISMATCH","分镜总时长与已确认目标不一致或无法由当前模型组成，请调整方案；系统未修改目标。"),
        RATIO("VIDEO_RATIO_UNSUPPORTED","画幅不在所选模型支持范围，请调整生成画幅。"),
        RESOLUTION("VIDEO_RESOLUTION_UNSUPPORTED","分辨率不在所选模型支持范围，请调整生成分辨率。"),
        REFERENCE("VIDEO_REFERENCE_UNSUPPORTED","所选模型不支持当前参考图片角色，请选择相应参考能力的模型。"),
        SCENE("VIDEO_SCENE_INVALID","分镜来源、画面或时长信息不完整，请检查对应分镜。"),
        PROMPT_LENGTH("VIDEO_PROMPT_TOO_LONG","视频提示词超出当前准备长度限制，请精简分镜内容后继续。"),
        PROMPT_OUTPUT("VIDEO_PROMPT_OUTPUT_INVALID","视频提示词准备未返回完整有效结果，进度已保留，尚未创建费用确认或生成任务。"),
        BATCH("VIDEO_BATCH_INVALID","整组生成规格不完整或不一致，请检查分镜方案。"),
        QUOTE("VIDEO_QUOTE_UNAVAILABLE","当前视频报价不可用，请检查模型计价配置后继续。");
        final String code,message;Reason(String code,String message){this.code=code;this.message=message;}
    }
    private final Reason reason;
    private final Integer ordinal;
    private final String safeDetail;
    private com.fasterxml.jackson.databind.JsonNode sourceRef;
    private ValidationRule validationRule;
    private String validationPath;
    private String diagnosticId;
    private com.fasterxml.jackson.databind.JsonNode repairInput;
    public VideoPreparationException(Reason reason){this(reason,null,reason.message);}
    private VideoPreparationException(Reason reason,Integer ordinal,String safeDetail) {
        super(400,(ordinal==null?"":"第 "+ordinal+" 幕：")+safeDetail);
        this.reason=reason;this.ordinal=ordinal;this.safeDetail=safeDetail;
    }
    public String code(){return reason.code;}
    public Integer sceneOrdinal(){return ordinal;}
    public VideoPreparationException atScene(int ordinal){
        var copy=new VideoPreparationException(reason,ordinal,safeDetail).withSource(sourceRef);
        copy.validationRule=validationRule;copy.validationPath=validationPath;copy.diagnosticId=diagnosticId;copy.repairInput=repairInput;return copy;
    }
    public static VideoPreparationException invalid(ValidationRule rule,String path) {
        var failure=new VideoPreparationException(rule==ValidationRule.SOURCE_SPEECH_INVALID?Reason.SCENE:Reason.PROMPT_OUTPUT);
        failure.validationRule=rule;failure.validationPath=path;return failure;
    }
    public String validationCode(){return validationRule==null?null:validationRule.name();}
    public String validationDetail(){return validationRule==null?null:validationPath+": "+validationRule.repairHint();}
    public String repairHint(){return validationRule==null?null:validationRule.repairHint();}
    public String diagnosticId(){return diagnosticId;}
    public com.fasterxml.jackson.databind.JsonNode repairInput(){return repairInput==null?null:repairInput.deepCopy();}
    /** Invoked only at capability/reference preparation boundaries, never pricing or submission. */
    public VideoPreparationException withRepairInput(com.fasterxml.jackson.databind.JsonNode input) {
        if(java.util.Set.of(Reason.MODEL,Reason.DURATION,Reason.REFERENCE).contains(reason))repairInput=input.deepCopy();
        return this;
    }
    public VideoPreparationException withDiagnosticId(String id){diagnosticId=id;return this;}
    public static VideoPreparationException unavailableReference(com.fasterxml.jackson.databind.JsonNode input) {
        return new VideoPreparationException(Reason.REFERENCE,null,"参考图片缺失、已过期或当前不可用，请选择本人有效图片；系统未移除参考要求。")
                .withRepairInput(input);
    }
    public VideoPreparationException withSource(com.fasterxml.jackson.databind.JsonNode source){this.sourceRef=source==null?null:source.deepCopy();return this;}
    public com.fasterxml.jackson.databind.JsonNode sourceRef(){return sourceRef;}
    public static VideoPreparationException unsupportedDuration(int requested,List<Integer> allowed,int min,int max) {
        String range=allowed==null||allowed.isEmpty()?min+"～"+max+" 秒":allowed+" 秒";
        return new VideoPreparationException(Reason.DURATION,null,"当前要求 "+requested+" 秒，所选模型支持 "+range+"。请调整分镜时长或选择合适模型；系统未自动修改规格。");
    }
}
