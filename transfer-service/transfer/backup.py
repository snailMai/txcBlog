"""Consistent archives and restore into a previously nonexistent directory."""

import json
import os
import sqlite3
import tempfile
import zipfile
from pathlib import Path

from .storage import ID_PATTERN, Problem, Store, sha256_file


def create_backup(store):
    archive = tempfile.TemporaryFile(dir=store.root / "work")
    try:
        with store.lock, tempfile.TemporaryDirectory(dir=store.root / "work") as work:
            snapshot = Path(work) / "history.sqlite3"
            with store.db() as source:
                destination = sqlite3.connect(snapshot)
                try:
                    source.backup(destination)
                finally:
                    destination.close()
            connection = sqlite3.connect(snapshot)
            connection.row_factory = sqlite3.Row
            try:
                rows = connection.execute("SELECT u.id,u.size,u.sha256,u.thumb_size FROM uploads u JOIN messages m ON m.id=u.message_id WHERE m.deleted=0").fetchall()
                connection.execute("DELETE FROM chunks")
                connection.execute("DELETE FROM uploads WHERE message_id IS NULL OR message_id IN (SELECT id FROM messages WHERE deleted=1)")
                connection.execute("DELETE FROM sessions")
                connection.execute("DELETE FROM login_attempts")
                connection.commit()
            finally:
                connection.close()
            manifest = {"format": 1, "files": {}}
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as output:
                output.write(snapshot, "history.sqlite3")
                manifest["database_sha256"] = sha256_file(snapshot)
                for row in rows:
                    for directory in ("files", "thumbs"):
                        if directory == "thumbs" and not row["thumb_size"]:
                            continue
                        source = store.root / directory / row["id"]
                        file_hash = sha256_file(source)
                        if directory == "files" and file_hash != row["sha256"]:
                            raise Problem("原文件校验失败，备份已停止", 422)
                        name = directory + "/" + row["id"]
                        manifest["files"][name] = {"sha256": file_hash, "size": source.stat().st_size}
                        output.write(source, name)
                output.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False))
        archive.seek(0)
        return archive
    except BaseException:
        archive.close()
        raise


def restore_backup(source, directory):
    target = Path(directory).resolve()
    if target.exists():
        raise Problem("恢复目标必须是尚不存在的新目录，禁止覆盖现有数据")
    target.parent.mkdir(parents=True, exist_ok=True)
    # Validate everything before creating the target, and never extract arbitrary paths.
    with zipfile.ZipFile(source) as archive:
        infos = archive.infolist()
        if len(infos) > 100_000 or len({i.filename for i in infos}) != len(infos):
            raise Problem("备份条目无效")
        if archive.getinfo("manifest.json").file_size > 16 * 1024 * 1024:
            raise Problem("备份清单过大")
        manifest = json.loads(archive.read("manifest.json"))
        if manifest.get("format") != 1 or not isinstance(manifest.get("files"), dict):
            raise Problem("备份格式不支持")
        expected = {"history.sqlite3", "manifest.json", *manifest["files"].keys()}
        if set(archive.namelist()) != expected:
            raise Problem("备份文件清单不匹配")
        if archive.getinfo("history.sqlite3").file_size > 1024 * 1024 * 1024:
            raise Problem("备份数据库过大")
        for name, item in manifest["files"].items():
            pieces = name.split("/")
            if len(pieces) != 2 or pieces[0] not in ("files", "thumbs") or not ID_PATTERN.fullmatch(pieces[1]):
                raise Problem("备份包含不安全的文件路径")
            if type(item.get("size")) is not int or item["size"] < 0 or archive.getinfo(name).file_size != item["size"]:
                raise Problem("备份文件大小不匹配")
        with tempfile.TemporaryDirectory(prefix="snail-restore-", dir=target.parent) as scratch:
            staging = Path(scratch)
            for name in ["history.sqlite3", *manifest["files"]]:
                output = staging / name
                output.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(name) as incoming, output.open("xb") as outgoing:
                    while block := incoming.read(1024 * 1024):
                        outgoing.write(block)
                expected_hash = manifest["database_sha256"] if name == "history.sqlite3" else manifest["files"][name]["sha256"]
                if sha256_file(output) != expected_hash:
                    raise Problem("备份校验失败，未执行恢复")
            connection = sqlite3.connect(staging / "history.sqlite3")
            connection.row_factory = sqlite3.Row
            try:
                if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise Problem("备份数据库损坏")
                actual = set()
                for row in connection.execute("SELECT * FROM uploads"):
                    if not ID_PATTERN.fullmatch(row["id"]) or row["state"] != "ready" or row["message_id"] is None:
                        raise Problem("备份数据库含有未完成附件")
                    file_name = "files/" + row["id"]
                    if manifest["files"].get(file_name, {}).get("sha256") != row["sha256"] or manifest["files"].get(file_name, {}).get("size") != row["size"]:
                        raise Problem("备份数据库与原文件不一致")
                    actual.add(file_name)
                    if row["thumb_size"]:
                        actual.add("thumbs/" + row["id"])
                if actual != set(manifest["files"]):
                    raise Problem("备份附件引用不完整")
                if connection.execute("PRAGMA foreign_key_check").fetchone():
                    raise Problem("备份数据库引用无效")
                connection.execute("DELETE FROM sessions")
                connection.commit()
            finally:
                connection.close()
            # The destination is absent; rename is atomic on the same filesystem.
            os.rename(staging, target)
            os.chmod(target, 0o700)
    restored = Store(target)
    restored.close()
