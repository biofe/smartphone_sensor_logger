"""Commands for the server owner, run on the VPS: python -m velowind_upload.admin <command>

  report [--days N]              rides waiting in quarantine, recent rejections, installs
  promote --all | INSTALL [RIDE]  move quarantined rides into the dataset
  trust | untrust | block | unblock INSTALL
  delete INSTALL                 remove everything an install sent (e.g. after an email request)
  cleanup                        drop old IP addresses and old rejected files (also runs hourly)

INSTALL may be the short form riders see in the app (the first 8 characters).
"""

import argparse
import sys
import time

from .config import Settings
from .store import Store, report_dict


def _when(utc: int) -> str:
    return time.strftime("%Y-%m-%d %H:%M", time.gmtime(utc)) + "Z"


def report(store: Store, days: int):
    since = int(time.time()) - days * 86_400
    waiting = store.quarantined()
    print(f"Quarantine: {len(waiting)} rides")
    for r in waiting:
        rep = report_dict(r)
        print(
            f"  {r['install_id'][:8]} {r['ride_name']:20} {_when(r['received_utc'])}"
            f"  {rep['duration_s'] / 60:5.1f} min {rep['distance_km']:6.2f} km"
            f"  in DE/FR/CH {rep['area_fraction']:.0%}  app {r['app_version'] or '?'}"
        )
    rejected = store.db.execute(
        "SELECT * FROM rejections WHERE received_utc >= ? ORDER BY received_utc DESC", (since,)
    ).fetchall()
    print(f"\nRejected in the last {days} days: {len(rejected)}")
    for r in rejected:
        print(
            f"  {r['install_id'][:8]} {r['ride_name'] or '-':20} {_when(r['received_utc'])}  {r['reason']}"
            f"{' (' + r['details'] + ')' if r['details'] else ''}{'  file: ' + r['file'] if r['file'] else ''}"
        )
    installs = store.db.execute(
        "SELECT i.*, (SELECT COUNT(*) FROM rides r WHERE r.install_id = i.install_id AND r.state = 'dataset') AS in_dataset"
        " FROM installs i ORDER BY last_seen_utc DESC"
    ).fetchall()
    print(f"\nInstalls: {len(installs)}")
    for i in installs:
        print(f"  {i['install_id'][:8]} {i['trust']:9} {i['in_dataset']:4} rides in dataset  last seen {_when(i['last_seen_utc'])}")


def main(argv=None):
    parser = argparse.ArgumentParser(prog="velowind_upload.admin", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("report")
    p.add_argument("--days", type=int, default=7)
    p = sub.add_parser("promote")
    p.add_argument("--all", action="store_true")
    p.add_argument("install", nargs="?")
    p.add_argument("ride", nargs="?")
    for name in ("trust", "untrust", "block", "unblock", "delete"):
        sub.add_parser(name).add_argument("install")
    sub.add_parser("cleanup")
    args = parser.parse_args(argv)

    store = Store(Settings.from_env())
    try:
        install = store.resolve_install(args.install) if getattr(args, "install", None) else None
    except KeyError as e:
        sys.exit(str(e.args[0]))

    if args.command == "report":
        report(store, args.days)
    elif args.command == "promote":
        if not args.all and not install:
            sys.exit("promote needs --all or an install")
        rides = store.quarantined(None if args.all else install)
        if args.ride:
            rides = [r for r in rides if r["ride_name"] == args.ride]
        for r in rides:
            store.promote(r["install_id"], r["ride_name"])
        print(f"Promoted {len(rides)} rides")
    elif args.command in ("trust", "untrust", "block", "unblock"):
        trust = {"trust": "trusted", "untrust": "untrusted", "block": "blocked", "unblock": "new"}[args.command]
        store.set_trust(install, trust)
        print(f"{install[:8]} is now {trust}")
    elif args.command == "delete":
        print(f"Deleted {store.delete_install(install)} rides of {install[:8]}")
    elif args.command == "cleanup":
        store.cleanup()


if __name__ == "__main__":
    main()
