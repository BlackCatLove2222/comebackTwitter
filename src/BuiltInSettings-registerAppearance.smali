# BuiltInSettings-registerAppearance.smali — jp21「外観」セレクタの登録ブロック（抜粋）
#
# 出所: jp21 納品 APK の classes21.dex に入っている
# app/morphe/extension/newx/settings/BuiltInSettings.smali からの抜粋。
# 本体は生成コード（load() 内の設定登録群）であり、jp21 で追加したのは
# (1) register() 末尾への registerAppearance() 呼び出し1行と、
# (2) registerAppearance() メソッドそのもの（下記）だけ。
# 同期本体は AppearanceSync.smali（同梱）を参照。手順は BUILD.md の B14。
#
# --- (1) register() 末尾への追加（1行） ---
#     invoke-static {}, Lapp/morphe/extension/newx/settings/BuiltInSettings;->registerAppearance()V
#
# --- (2) 追加メソッド ---
.method private static registerAppearance()V
    .registers 7

    :try_start_0
    const-string v0, "newx.appearance.theme"

    const-string v1, "newx.theme.appearance"

    const-string v2, "piko_newx_category_appearance_title"

    const-string v3, "piko_newx_category_appearance_summary"

    const/16 v4, 0x32

    const/4 v5, 0x1

    invoke-static/range {v0 .. v5}, Lapp/morphe/extension/crimera/settings/SettingsRegistry;->registerSingleChoice(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IZ)V

    const-string v0, "newx.theme.appearance"

    const-string v1, "system"

    const/4 v2, 0x1

    invoke-static {v0, v1, v2}, Lapp/morphe/extension/crimera/settings/SettingsRegistry;->configureSingleChoice(Ljava/lang/String;Ljava/lang/String;Z)V

    const-string v0, "newx.theme.appearance"

    const-string v1, "light"

    const-string v2, "grok_settings_light"

    const/4 v3, 0x0

    invoke-static {v0, v1, v2, v3}, Lapp/morphe/extension/crimera/settings/SettingsRegistry;->registerChoiceOption(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V

    const-string v0, "newx.theme.appearance"

    const-string v1, "dim"

    const-string v2, "piko_newx_theme_dim"

    const/4 v3, 0x0

    invoke-static {v0, v1, v2, v3}, Lapp/morphe/extension/crimera/settings/SettingsRegistry;->registerChoiceOption(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V

    const-string v0, "newx.theme.appearance"

    const-string v1, "black"

    const-string v2, "piko_newx_theme_contrast"

    const/4 v3, 0x0

    invoke-static {v0, v1, v2, v3}, Lapp/morphe/extension/crimera/settings/SettingsRegistry;->registerChoiceOption(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V

    const-string v0, "newx.theme.appearance"

    const-string v1, "system"

    const-string v2, "settings_dark_mode_system"

    const/4 v3, 0x1

    invoke-static {v0, v1, v2, v3}, Lapp/morphe/extension/crimera/settings/SettingsRegistry;->registerChoiceOption(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V
    :try_end_3e
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_3e} :catch_3f

    return-void

    :catch_3f
    move-exception v0

    const-string v1, "NewXAppearance"

    const-string v2, "Failed to register unified appearance setting"

    invoke-static {v1, v2, v0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    return-void
.end method
