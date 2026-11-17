.class public final Lpiko/jp135/SentBanner;
.super Ljava/lang/Object;
.source "SourceFile"


# static fields
.field private static sAct:Ljava/lang/ref/WeakReference;

.field private static sPending:Lpiko/jp135/SentBanner$k;


# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method private static declared-synchronized claim(Lpiko/jp135/SentBanner$k;)Z
    .registers 2

    sget-object v0, Lpiko/jp135/SentBanner;->sPending:Lpiko/jp135/SentBanner$k;

    if-eqz v0, :cond_0

    const/4 v0, 0x0

    return v0

    :cond_0
    sput-object p0, Lpiko/jp135/SentBanner;->sPending:Lpiko/jp135/SentBanner$k;

    const/4 v0, 0x1

    return v0
.end method

.method private static currentActivity()Landroid/app/Activity;
    .registers 3

    :try_start_0
    sget-object v0, Lpiko/jp135/SentBanner;->sAct:Ljava/lang/ref/WeakReference;

    if-eqz v0, :cond_null

    invoke-virtual {v0}, Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;

    move-result-object v0

    check-cast v0, Landroid/app/Activity;

    if-eqz v0, :cond_null

    invoke-virtual {v0}, Landroid/app/Activity;->isFinishing()Z

    move-result v1

    if-nez v1, :cond_null

    invoke-virtual {v0}, Landroid/app/Activity;->isDestroyed()Z

    move-result v1

    if-nez v1, :cond_null

    return-object v0

    :cond_null
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    const/4 v0, 0x0

    return-object v0

    :catchall_0
    const/4 v0, 0x0

    return-object v0
.end method

.method private static resolveText(Landroid/app/Activity;Lcom/x/inappnotification/api/a;)Ljava/lang/String;
    .registers 7

    :try_start_0
    iget-object v0, p1, Lcom/x/inappnotification/api/a;->a:Lcom/x/models/jd;

    instance-of v1, v0, Lcom/x/models/id;

    if-eqz v1, :cond_fail

    check-cast v0, Lcom/x/models/id;

    iget v1, v0, Lcom/x/models/id;->b:I

    if-eqz v1, :cond_fail

    iget-object v0, v0, Lcom/x/models/id;->c:Ljava/util/List;

    if-eqz v0, :cond_simple

    invoke-interface {v0}, Ljava/util/List;->isEmpty()Z

    move-result v2

    if-nez v2, :cond_simple

    const/4 v2, 0x0

    new-array v2, v2, [Ljava/lang/Object;

    invoke-interface {v0, v2}, Ljava/util/List;->toArray([Ljava/lang/Object;)[Ljava/lang/Object;

    move-result-object v0

    invoke-virtual {p0, v1, v0}, Landroid/app/Activity;->getString(I[Ljava/lang/Object;)Ljava/lang/String;

    move-result-object v0

    return-object v0

    :cond_simple
    invoke-virtual {p0, v1}, Landroid/app/Activity;->getString(I)Ljava/lang/String;

    move-result-object v0

    return-object v0

    :cond_fail
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    const/4 v0, 0x0

    return-object v0

    :catchall_0
    const/4 v0, 0x0

    return-object v0
.end method

.method private static declared-synchronized takePending()Lpiko/jp135/SentBanner$k;
    .registers 2

    sget-object v0, Lpiko/jp135/SentBanner;->sPending:Lpiko/jp135/SentBanner$k;

    const/4 v1, 0x0

    sput-object v1, Lpiko/jp135/SentBanner;->sPending:Lpiko/jp135/SentBanner$k;

    return-object v0
.end method


# virtual methods
.method public static attach(Landroid/app/Activity;)V
    .registers 2

    if-nez p0, :cond_0

    return-void

    :cond_0
    :try_start_0
    new-instance v0, Ljava/lang/ref/WeakReference;

    invoke-direct {v0, p0}, Ljava/lang/ref/WeakReference;-><init>(Ljava/lang/Object;)V

    sput-object v0, Lpiko/jp135/SentBanner;->sAct:Ljava/lang/ref/WeakReference;
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    return-void

    :catchall_0
    return-void
.end method

.method public static finish(I)V
    .registers 4

    :try_start_0
    invoke-static {}, Lpiko/jp135/SentBanner;->takePending()Lpiko/jp135/SentBanner$k;

    move-result-object v0

    if-eqz v0, :cond_ret

    if-nez p0, :cond_click

    sget-object v1, Lcom/x/inappnotification/api/i;->AutoDismiss:Lcom/x/inappnotification/api/i;

    goto :goto_resume

    :cond_click
    sget-object v1, Lcom/x/inappnotification/api/i;->MessageClick:Lcom/x/inappnotification/api/i;

    :goto_resume
    invoke-interface {v0, v1}, Lkotlin/coroutines/Continuation;->resumeWith(Ljava/lang/Object;)V

    :cond_ret
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    return-void

    :catchall_0
    return-void
.end method

.method public static show(Lcom/x/composer/upload/v2/notifications/n;Lcom/x/inappnotification/api/a;Lcom/x/inappnotification/api/n;Lkotlin/coroutines/jvm/internal/ContinuationImpl;)Ljava/lang/Object;
    .registers 14

    instance-of v0, p3, Lpiko/jp135/SentBanner$k;

    if-eqz v0, :cond_new

    move-object v0, p3

    check-cast v0, Lpiko/jp135/SentBanner$k;

    iget v1, v0, Lpiko/jp135/SentBanner$k;->w:I

    const/high16 v2, -0x80000000

    and-int v3, v1, v2

    if-eqz v3, :cond_new

    sub-int/2addr v1, v2

    iput v1, v0, Lpiko/jp135/SentBanner$k;->w:I

    goto :goto_machine

    :cond_new
    new-instance v0, Lpiko/jp135/SentBanner$k;

    invoke-direct {v0, p0, p1, p2, p3}, Lpiko/jp135/SentBanner$k;-><init>(Lcom/x/composer/upload/v2/notifications/n;Lcom/x/inappnotification/api/a;Lcom/x/inappnotification/api/n;Lkotlin/coroutines/jvm/internal/ContinuationImpl;)V

    :goto_machine
    iget-object v3, v0, Lpiko/jp135/SentBanner$k;->u:Ljava/lang/Object;

    sget-object v4, Lkotlin/coroutines/intrinsics/CoroutineSingletons;->COROUTINE_SUSPENDED:Lkotlin/coroutines/intrinsics/CoroutineSingletons;

    iget v5, v0, Lpiko/jp135/SentBanner$k;->w:I

    if-eqz v5, :cond_label0

    const/4 v1, 0x1

    if-ne v5, v1, :cond_illegal

    invoke-static {v3}, Lkotlin/ResultKt;->b(Ljava/lang/Object;)V

    return-object v3

    :cond_illegal
    const-string v0, "call to \'resume\' before \'invoke\' with coroutine"

    invoke-static {v0}, Landroidx/room/u;->x(Ljava/lang/String;)V

    const/4 v0, 0x0

    return-object v0

    :cond_label0
    invoke-static {v3}, Lkotlin/ResultKt;->b(Ljava/lang/Object;)V

    invoke-static {}, Lpiko/jp135/SentBanner;->currentActivity()Landroid/app/Activity;

    move-result-object v6

    if-eqz v6, :cond_delegate

    invoke-static {v6, p1}, Lpiko/jp135/SentBanner;->resolveText(Landroid/app/Activity;Lcom/x/inappnotification/api/a;)Ljava/lang/String;

    move-result-object v7

    if-eqz v7, :cond_delegate

    invoke-static {v0}, Lpiko/jp135/SentBanner;->claim(Lpiko/jp135/SentBanner$k;)Z

    move-result v8

    if-eqz v8, :cond_busy

    const/4 v1, 0x1

    iput v1, v0, Lpiko/jp135/SentBanner$k;->w:I

    :try_start_0
    sget-object v1, Lpiko/jp135/SentBanner$Fin;->a:Lpiko/jp135/SentBanner$Fin;

    invoke-static {v6, v7, v1}, Lpiko/jp135/BannerUi;->show(Landroid/app/Activity;Ljava/lang/String;Lpiko/jp135/BannerUi$Done;)V
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    return-object v4

    :catchall_0
    invoke-static {}, Lpiko/jp135/SentBanner;->takePending()Lpiko/jp135/SentBanner$k;

    :cond_delegate
    invoke-virtual {p0, p1, p2, p3}, Lcom/x/composer/upload/v2/notifications/n;->e(Lcom/x/inappnotification/api/a;Lcom/x/inappnotification/api/n;Lkotlin/coroutines/jvm/internal/ContinuationImpl;)Ljava/lang/Enum;

    move-result-object v0

    return-object v0

    :cond_busy
    sget-object v0, Lcom/x/inappnotification/api/i;->AutoDismiss:Lcom/x/inappnotification/api/i;

    return-object v0
.end method
