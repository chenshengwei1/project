# 创建项目
 - 第一步：创建目录结构
        你的项目名/
        ├── pom.xml
        └── src/
            └── main/
                ├── java/
                │   └── com/example/demo/
                └── resources/
- 第二步：编写 pom.xml
- 第三步：创建启动类
- 第四步： 复制其它目录的 mvn
    mvnw
    mvnw.cmd
    .mvn 整个文件夹（这是关键，包含配置和可能的 wrapper jar）
- 第五步：在 PowerShell 里执行：
```.\mvnw.cmd spring-boot:run```

# 默认错误页面跳转
方式一：放在静态资源目录（最简单，推荐）
在你已有的 src/main/resources/static/ 目录下，新建一个名为 error 的文件夹，然后把你的 404.html 放进去。

最终路径是：src/main/resources/static/error/404.html。

方式二：放在模板目录（如果你用了 Thymeleaf）
如果你项目里引入了 Thymeleaf 模板引擎，可以把文件放在 src/main/resources/templates/error/404.html。这样你可以在页面里用 Thymeleaf 语法展示更丰富的动态信息。

AgentProperties 
   - 通过@ConfigurationProperties(prefix = "ai.agent") 注解，读取src\main\resources\application.yml路径下面配置
   - 通过 @Data注解， Lombok 库提供的一个组合注解。它的核心作用是在编译阶段自动生成样板代码，让你不用手写那些冗长的 getter、setter、toString 等方法，使实体类（Entity/POJO）保持干净。
   - 通过@Component注解，就会把这个类实例化成一个 Bean，放进 Spring 容器里。之后你就可以在别的地方通过 @Autowired 注入它来使用，而不需要自己 new。

   - 通过@PostConstruct注解
     生命周期注解，被 Spring 框架完整支持。它的作用很明确：标记一个方法，让 Spring 在完成该 Bean 的依赖注入之后、正式使用之前，自动调用它一次