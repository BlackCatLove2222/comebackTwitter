# jp22 (B15-2): ReplySortingResolver.getDefault() の無条件化 — 抜粋
# 配置先: classes21 / Lapp/morphe/extension/newx/misc/ReplySortingResolver;
# 本体はこの形に置き換える（従来の解決順はすべてバイパスされる）

.method public static getDefault()Ljava/lang/String;
    .locals 1

    .line 61
    const-string v0, "Relevance"

    return-object v0
.end method
