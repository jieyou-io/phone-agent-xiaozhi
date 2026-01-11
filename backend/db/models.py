from __future__ import annotations

from sqlalchemy import Boolean, DateTime, Integer, String, Text, func, text
from sqlalchemy.dialects.mysql import BIGINT, JSON, TINYINT
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.schema import Computed, Index

from config.settings import settings


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    __table_args__ = {
        "mysql_charset": settings.db_charset,
        "mysql_collate": settings.db_collation,
        "mysql_comment": "用户（可选账号绑定）",
    }

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    display_name: Mapped[str | None] = mapped_column(String(128))
    email: Mapped[str | None] = mapped_column(String(255))
    status: Mapped[int] = mapped_column(TINYINT, nullable=False, server_default="1")
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[object | None] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        server_onupdate=func.now(),
    )


class Device(Base):
    __tablename__ = "devices"
    __table_args__ = (
        Index("uq_devices_device_id", "device_id", unique=True),
        Index("idx_devices_user_id", "user_id"),
        Index("idx_devices_status_last_seen", "status", "last_seen"),
        {
            "mysql_charset": settings.db_charset,
            "mysql_collate": settings.db_collation,
            "mysql_comment": "设备（以 device_id 为主标识）",
        },
    )

    id: Mapped[int] = mapped_column(BIGINT(unsigned=True), primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(36), nullable=False)
    user_id: Mapped[int | None] = mapped_column(Integer)
    model: Mapped[str | None] = mapped_column(String(128))
    os_version: Mapped[str | None] = mapped_column(String(64))
    app_version: Mapped[str | None] = mapped_column(String(32))
    status: Mapped[int] = mapped_column(TINYINT, nullable=False, server_default="1")
    last_seen: Mapped[object | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[object | None] = mapped_column(
        DateTime(timezone=True),
        server_default=text("NULL"),
        server_onupdate=func.now(),
    )


class Skill(Base):
    __tablename__ = "skills"
    __table_args__ = (
        Index("uq_skills_skill_id", "skill_id", unique=True),
        Index("idx_skills_owner_active", "owner_device_id", "is_active"),
        Index("idx_skills_builtin_active", "is_builtin", "is_active"),
        Index("idx_skills_parent", "parent_skill_id"),
        {
            "mysql_charset": settings.db_charset,
            "mysql_collate": settings.db_collation,
            "mysql_comment": "技能（内置 + 用户自定义）",
        },
    )

    id: Mapped[int] = mapped_column(BIGINT(unsigned=True), primary_key=True, autoincrement=True)
    skill_id: Mapped[str] = mapped_column(String(64), nullable=False)
    owner_device_id: Mapped[str | None] = mapped_column(String(36))
    parent_skill_id: Mapped[str | None] = mapped_column(String(64))
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False)
    definition: Mapped[dict] = mapped_column(JSON, nullable=False)
    is_builtin: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="0")
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="1")
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[object | None] = mapped_column(
        DateTime(timezone=True),
        server_default=text("NULL"),
        server_onupdate=func.now(),
    )


class ModelConfig(Base):
    __tablename__ = "model_configs"
    __table_args__ = (
        Index("uq_model_configs_scope", "scope_key", unique=True),
        Index("idx_model_configs_device", "owner_device_id"),
        Index("idx_model_configs_skill", "skill_id"),
        {
            "mysql_charset": settings.db_charset,
            "mysql_collate": settings.db_collation,
            "mysql_comment": "模型配置（三级优先级配置）",
        },
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    owner_device_id: Mapped[str | None] = mapped_column(String(36))  # NULL = 全局配置
    skill_id: Mapped[str | None] = mapped_column(String(64))
    provider: Mapped[str] = mapped_column(String(64), nullable=False)
    base_url: Mapped[str] = mapped_column(String(255), nullable=False)
    api_key: Mapped[str] = mapped_column(String(255), nullable=False)
    model: Mapped[str] = mapped_column(String(128), nullable=False)
    config: Mapped[dict | None] = mapped_column(String(2048), nullable=False)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[object | None] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        server_onupdate=func.now(),
    )
    scope_key: Mapped[str] = mapped_column(
        String(200),
        Computed("CONCAT(IFNULL(owner_device_id, '_global'), ':', IFNULL(skill_id, '_default'))", persisted=True),
    )


class BaiduSpeechConfig(Base):
    __tablename__ = "baidu_speech_configs"
    __table_args__ = (
        Index("uq_baidu_speech_configs_owner_device_id", "owner_device_id", unique=True),
        {
            "mysql_charset": settings.db_charset,
            "mysql_collate": settings.db_collation,
            "mysql_comment": "百度语音配置（按设备）",
        },
    )

    id: Mapped[int] = mapped_column(BIGINT(unsigned=True), primary_key=True, autoincrement=True)
    owner_device_id: Mapped[str] = mapped_column(String(36), nullable=False)
    app_id: Mapped[str] = mapped_column(String(128), nullable=False)
    api_key: Mapped[str] = mapped_column(String(255), nullable=False)
    secret_key: Mapped[str] = mapped_column(String(255), nullable=False)
    config: Mapped[dict | None] = mapped_column(String(2048), nullable=False)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[object | None] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        server_onupdate=func.now(),
    )


class UsageLog(Base):
    __tablename__ = "usage_logs"
    __table_args__ = (
        Index("idx_usage_logs_device_time", "device_id", "created_at"),
        Index("idx_usage_logs_skill_time", "skill_id", "created_at"),
        Index("idx_usage_logs_status_time", "status", "created_at"),
        {
            "mysql_charset": settings.db_charset,
            "mysql_collate": settings.db_collation,
            "mysql_comment": "使用日志（统计 + 审计）",
        },
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(36), nullable=False)
    skill_id: Mapped[str] = mapped_column(String(64), nullable=False)
    task_text: Mapped[str | None] = mapped_column(Text)
    status: Mapped[int] = mapped_column(TINYINT, nullable=False)
    execution_ms: Mapped[int | None] = mapped_column(Integer)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), server_default=func.now())


class SkillInvocation(Base):
    __tablename__ = "skill_invocations"
    __table_args__ = (
        Index("idx_skill_invocations_device_time", "device_id", "created_at"),
        Index("idx_skill_invocations_skill_time", "skill_id", "created_at"),
        Index("idx_skill_invocations_status_time", "status", "created_at"),
        {
            "mysql_charset": settings.db_charset,
            "mysql_collate": settings.db_collation,
            "mysql_comment": "按技能调用日志（耗时 + 频率）",
        },
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(36), nullable=False)
    skill_id: Mapped[str] = mapped_column(String(64), nullable=False)
    task_text: Mapped[str | None] = mapped_column(Text)
    status: Mapped[int] = mapped_column(TINYINT, nullable=False)
    execution_ms: Mapped[int | None] = mapped_column(Integer)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), server_default=func.now())


class DeviceSession(Base):
    __tablename__ = "device_sessions"
    __table_args__ = (
        Index("uq_device_sessions_session_id", "session_id", unique=True),
        Index("idx_device_sessions_device_time", "device_id", "connected_at"),
        Index("idx_device_sessions_connected", "connected_at"),
        {
            "mysql_charset": settings.db_charset,
            "mysql_collate": settings.db_collation,
            "mysql_comment": "设备会话（WebSocket 跟踪）",
        },
    )

    id: Mapped[int] = mapped_column(BIGINT(unsigned=True), primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(String(36), nullable=False)
    device_id: Mapped[str] = mapped_column(String(36), nullable=False)
    connected_at: Mapped[object] = mapped_column(DateTime(timezone=True), server_default=func.now())
    disconnected_at: Mapped[object | None] = mapped_column(DateTime(timezone=True))
    ip_address: Mapped[str | None] = mapped_column(String(45))
    user_agent: Mapped[str | None] = mapped_column(String(255))
