import argparse
import getpass
import json
import os
import sys
from pathlib import Path

from .storage import Problem, Store


def main():
    parser = argparse.ArgumentParser(description="蜗牛传送：自己的设备，随手传")
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run", help="启动单实例服务")
    run.add_argument("--data", default="data")
    run.add_argument("--port", type=int, default=8765)
    run.add_argument("--origin", default=os.environ.get("TRANSFER_ORIGIN"))
    run.add_argument("--prefix", default="", help="工具站挂载路径，例如 /transfer；缺省为独立根路径")
    run.add_argument("--quota-gb", type=float, default=20)
    initialize = commands.add_parser("init", help="在终端设置首次访问密码")
    initialize.add_argument("--data", default="data")
    initialize.add_argument("--password-stdin", action="store_true", help="从标准输入读取密码，勿使用命令行密码参数")
    backup = commands.add_parser("backup", help="停服后导出备份；运行中请使用网页导出")
    backup.add_argument("--data", default="data")
    backup.add_argument("--output", required=True)
    restore = commands.add_parser("restore", help="恢复至尚不存在的新目录")
    restore.add_argument("archive")
    restore.add_argument("--data", required=True)
    args = parser.parse_args()
    try:
        if args.command == "run":
            if not 1 <= args.port <= 65535 or not 0 < args.quota_gb <= 100_000:
                parser.error("端口或配额无效")
            from waitress import serve
            from .app import create_app
            app = create_app(args.data, args.origin or f"http://127.0.0.1:{args.port}", int(args.quota_gb * 1_000_000_000), prefix=args.prefix)
            if not app.extensions["store"].configured() and not app.config["PUBLIC_ORIGIN"].startswith("http://127.0.0.1:"):
                raise Problem("请先执行 init，在本机设置访问密码后再开放域名")
            print("访问入口：" + app.config["PUBLIC_ORIGIN"] + args.prefix + "/", flush=True)
            print(f"本机监听：127.0.0.1:{args.port}；数据：{app.extensions['store'].root}", flush=True)
            serve(app, host="127.0.0.1", port=args.port, threads=6,
                  max_request_body_size=9 * 1024 * 1024, channel_timeout=180,
                  clear_untrusted_proxy_headers=True)
        elif args.command == "init":
            password = sys.stdin.readline().rstrip("\r\n") if args.password_stdin else getpass.getpass("设置访问密码（至少 10 个字符）：")
            if not args.password_stdin and password != getpass.getpass("再次输入密码："):
                raise Problem("两次密码不一致")
            store = Store(args.data)
            try:
                store.configure(password)
            finally:
                store.close()
            print("初始化完成，密码仅保存为不可逆哈希。")
        elif args.command == "backup":
            from .backup import create_backup
            destination = Path(args.output)
            if destination.exists():
                raise Problem("备份目标已经存在，请使用新文件名")
            store = Store(args.data)
            try:
                with create_backup(store) as source, destination.open("xb") as output:
                    os.chmod(destination, 0o600)
                    while block := source.read(1024 * 1024):
                        output.write(block)
            finally:
                store.close()
            print("备份完成：" + str(destination.resolve()))
        elif args.command == "restore":
            from .backup import restore_backup
            restore_backup(args.archive, args.data)
            print("恢复完成，请使用原密码登录；旧会话已失效。")
    except (Problem, RuntimeError, ValueError, OSError) as error:
        parser.exit(1, f"错误：{error.message if isinstance(error, Problem) else str(error)}\n")


if __name__ == "__main__":
    main()
