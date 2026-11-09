# 第一版即使用 PostgreSQL 和 pgvector

下一阶段计划加入检索增强生成（RAG）。为了让写作记录、知识分块、向量和元数据共用一套存储，第一版直接使用本地 PostgreSQL 和 pgvector。这样可以避免后续数据库迁移和额外的向量服务，代价是需要运行本地 PostgreSQL 容器。
