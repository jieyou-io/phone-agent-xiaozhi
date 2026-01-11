import bcrypt


def hash_password(plain: str) -> str:
    """生成密码哈希值"""
    salt = bcrypt.gensalt()
    return bcrypt.hashpw(plain.encode("utf-8"), salt).decode("utf-8")


def verify_password(plain: str, hashed: str) -> bool:
    """验证密码是否匹配"""
    return bcrypt.checkpw(plain.encode("utf-8"), hashed.encode("utf-8"))
