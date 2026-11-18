# R8 规则（release 构建）。debug 构建不跑 R8，所以这些规则只影响发布包。
#
# 判断依据：本项目**不用反射解析模型**（Message/Conversation 全部手写 codec，
# 直接读 kotlinx JsonElement），所以需要的规则只有两类：
#   1) kotlinx.serialization 的编译器插件生成物（$$serializer / Companion.serializer()）
#   2) 少数三方库的可选依赖（okhttp 的 conscrypt/bouncycastle 分支）

-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# --- kotlinx.serialization ---------------------------------------------------
# 序列化器由编译器插件生成，R8 看不到调用点，必须整类保留。
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.aichat.app.**$$serializer { *; }
-keepclassmembers class com.aichat.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.aichat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 枚举：R8 会裁掉未引用的枚举常量，而我们把枚举名写进了 JSON（Role / ProviderType / Slot / MissReason 等）
-keepclassmembers enum com.aichat.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# --- 三方库 ------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlinx.serialization.**