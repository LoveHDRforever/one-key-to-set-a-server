# chatbridge 的编译依赖

这个子工程是**客户端**补丁（e33chat 侧边栏按钮），编译期需要 e33chat 本体：

```
e33chat-forge-2.4.17.jar
```

放到本目录（`chatbridge/libs/`）后再执行：

```bash
cd chatbridge
./gradlew build
```

e33chat 从 CurseForge 下载，**不要提交到仓库**（`.gitignore` 已忽略 `*.jar`，只保留本说明）。
