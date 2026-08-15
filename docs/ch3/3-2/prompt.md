请在 loop-project/backend 下创建 Spring Boot 后端骨架，只做本节增量。

本节要解决的问题：让浏览器能连上一个 Java 服务，
并为后续接入百炼模型准备好依赖与配置。

当前已有能力：无。目录里只有 README.md 和 .gitignore。

技术基线：Java 21 / Maven 3.9+ / Spring Boot 3.5.8 /
Spring AI 1.1.2（用 spring-ai-bom）/ 模型 qwen3.8-max（百炼 OpenAI 兼容端点）。

需要新增：
1. pom.xml：parent 用 spring-boot-starter-parent 3.5.8；
   依赖 spring-boot-starter-web、spring-ai-starter-model-openai、
   spring-boot-starter-test；dependencyManagement 导入 spring-ai-bom 1.1.2
2. 启动类 com.looptrip.LoopTripApplication
3. application.yml：端口 8080；spring.ai.openai 的 base-url 指向百炼兼容端点，
   api-key 从环境变量 DASHSCOPE_API_KEY 读取，chat.options.model=qwen3.8-max；
   自定义配置 looptrip.chapter=3
4. GET /api/meta：返回 chapter、model、capabilities、notDoneYet 四个字段
5. 一个上下文冒烟测试：test profile 用占位 Key，断言上下文能启动、
   ChatClient.Builder 已装配；测试不得访问真实模型

明确不做：不写任何工具（@Tool）、不写循环、不接数据库、不做行程业务接口。

验收场景：
正常——mvn test 通过；mvn spring-boot:run 后 curl /api/meta 返回 JSON
失败——不设置 DASHSCOPE_API_KEY 时，启动应当直接失败并给出明确原因

先输出修改计划、影响文件和测试计划，等我确认后再实现。

---------------------------------------------

请在 loop-project/web 下创建前端工程骨架，只做本节增量。

本节要解决的问题：让浏览器能打开一个页面，
并证明它能读到后端的数据。

技术基线：Vite 7 + React 19 + TypeScript + Tailwind 4，Node 20+。

需要新增：
1. package.json / vite.config.ts / tsconfig.json
2. Vite 开发代理：/api 转发到 http://localhost:8080
3. 一个最简页面：顶部标题栏显示项目名，
   右上角显示从 GET /api/meta 读到的 chapter 与 model
4. Tailwind 通过 @tailwindcss/vite 插件接入，不单独维护 CSS 文件

明确不做：不做对话框、不做行程展示、不做任何模型调用。
验收场景：npm run dev 打开 5173，右上角显示 "Ch3 · qwen3.8-max"。

先输出计划再实现。