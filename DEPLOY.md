# WriteLab 部署与启动说明

本文件记录从零启动 WriteLab 的完整步骤，覆盖开发模式与生产模式。所有命令在仓库根目录执行，除非另有说明。

## 1. 环境要求

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 25 | 后端运行 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 20+（含 npm） | 前端构建与运行 |
| Docker | 任意近期版本 | 启动 PostgreSQL |

确认工具链：

```bash
java -version
mvn -v
node -v
npm -v
docker --version
```

## 2. 配置环境变量

首次部署时从模板复制配置文件（仓库根目录）：

```bash
cp .env.example .env
```

按需编辑 `.env`：

- `SPRING_DATASOURCE_*`：数据库连接，默认与 `docker-compose.yml` 中的 postgres 一致。
- `WRITELAB_CORS_ALLOWED_ORIGINS`：允许访问后端的前端地址，多个用逗号分隔。
- `WRITELAB_MODEL_API_KEY`：留空时后端运行在确定性 Mock 模式，不调用真实模型。
- `WRITELAB_MODEL_BASE_URL` / `WRITELAB_MODEL_NAME`：真实模型接入时填写。

后端启动时会自动从工作目录或仓库根目录加载 `.env`（`backend/src/main/resources/application.yml:4-8`），无需手动 export。

## 3. 启动数据库

```bash
docker compose up -d postgres
```

等待健康检查通过：

```bash
docker compose ps
```

`writelab-postgres` 显示 `healthy` 即可继续。数据库结构由 Flyway 在后端启动时自动迁移。

## 4. 启动后端

开发模式（后台监听 `8080`）：

```bash
cd backend
mvn spring-boot:run
```

或在仓库根目录执行：

```bash
mvn -f backend/pom.xml spring-boot:run
```

验证存活：

```bash
curl http://localhost:8080/api/practices
```

返回 JSON 数组（可能是空数组）即表示后端已就绪。

### 生产模式（打包后运行）

```bash
cd backend
mvn clean package
java -jar target/writelab-backend-0.1.0.jar
```

- 端口可用 `SERVER_PORT` 覆盖，默认 `8080`。
- 运行 jar 时仍需能读取到 `.env`，或在启动环境中直接提供同名环境变量。

### 后端测试

```bash
cd backend
mvn test
```

测试基于 Testcontainers，需要本机 Docker 正在运行。

## 5. 启动前端

首次安装依赖：

```bash
cd frontend
npm install
```

开发模式：

```bash
cd frontend
npm run dev
```

访问 `http://127.0.0.1:5173`。Vite 已固定绑定 IPv4 回环地址（`frontend/vite.config.ts`），不要改用 `localhost`，本机 `::1` 不可达。

前端默认请求 `http://localhost:8080`。如果后端不在该地址，构建/启动前设置：

```bash
VITE_API_BASE_URL=http://<后端地址>:<端口> npm run dev
```

### 生产构建

```bash
cd frontend
npm run build
```

产物在 `frontend/dist/`，交给任意静态文件服务器托管。构建前通过 `VITE_API_BASE_URL` 写入后端地址（该值在构建时固化）：

```bash
VITE_API_BASE_URL=http://<后端地址>:<端口> npm run build
```

本地预览构建产物：

```bash
cd frontend
npm run preview
```

注意：生产环境部署到新域名/端口时，必须把该来源加入后端的 `WRITELAB_CORS_ALLOWED_ORIGINS`，否则浏览器会被 CORS 拦截。

## 6. 启动顺序与端口

| 步骤 | 服务 | 默认端口 |
| --- | --- | --- |
| 1 | PostgreSQL | 5432（可用 `POSTGRES_PORT` 覆盖） |
| 2 | 后端 | 8080（可用 `SERVER_PORT` 覆盖） |
| 3 | 前端 | 5173（开发模式） |

每次部署按「数据库 → 后端 → 前端」顺序启动。

## 7. 常见问题

| 现象 | 排查 |
| --- | --- |
| 后端启动报数据库连接失败 | 先确认 `docker compose ps` 中 postgres 为 healthy，再检查 `.env` 连接串与端口 |
| 后端启动报 Flyway 迁移失败 | 确认连到的是预期的数据库实例，避免误指向已有数据的库 |
| 前端提示「无法连接后端服务」 | 后端未启动、端口不符，或 `VITE_API_BASE_URL` 指向错误 |
| 浏览器报 CORS 错误 | 将前端实际访问地址加入 `WRITELAB_CORS_ALLOWED_ORIGINS` 并重启后端 |
| 页面能开但打开 `localhost:5173` 空白 | 改用 `http://127.0.0.1:5173` |
| 模型调用超时 | 调整 `WRITELAB_MODEL_TIMEOUT` / `WRITELAB_MODEL_MAX_RETRIES`，最坏等待为 timeout × (1 + max-retries) |

## 8. 停止服务

```bash
docker compose stop postgres
```

前端与后端在各自终端按 `Ctrl+C` 停止。彻底移除数据库容器（保留数据卷）：

```bash
docker compose down
```
