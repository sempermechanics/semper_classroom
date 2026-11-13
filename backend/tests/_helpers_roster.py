"""Roster setup shared by test_roster_bulk.py and test_roster_domain_lock.py:
licences minted by staff, approved Demo accounts, a caller signed in with a
second factor, and the IT and staff stores their fixtures start from."""

import time

import fake_firestore
import repo_view as repo
from app import deps
from app.config import settings

IT_EMAIL = "it@uni-a.edu"


def _mint(domain: str, admin: str, **kw) -> str:
    return repo.create_institution_license(
        domain_lock=domain, admin_emails=[admin], created_by_uid="staff", **kw,
    )["license"]["id"]


def _account(store, uid: str, email: str, **extra) -> None:
    store._data.setdefault("users", {})[uid] = {
        "email": email, "access_status": "APPROVED", "emailVerified": True, "plan": "demo", **extra,
    }


def _as(monkeypatch, uid: str, email: str, *, role: str = "user") -> None:
    """Sign every request in as this verified, second-factor account."""
    monkeypatch.setattr(
        deps, "verify_id_token",
        lambda _t: {"sub": uid, "email": email, "email_verified": True,
                    "firebase": {"sign_in_second_factor": "totp"},
                    "auth_time": time.time() - 60},
    )
    profile = {"uid": uid, "email": email, "role": role,
               "access_status": "APPROVED", "emailVerified": True}
    repo.patch(monkeypatch, "get_or_create_user", lambda claims, device_id=None: dict(profile))


def _it_store(monkeypatch, audited):
    """IT for licence A (`store.a`, uni-a.edu) with the console's MFA on;
    licence B (`store.b`, uni-b.edu) belongs to another institution.
    Requests carry `store.bearer`."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(settings, "DEV_INSECURE_AUTH", False)
    monkeypatch.setattr(settings, "ADMIN_WEB_MFA_ENABLED", True)
    monkeypatch.setattr(settings, "ADMIN_WEB_REAUTH_SECONDS", 900)
    _as(monkeypatch, "it-a", IT_EMAIL)
    store._data["users"] = {}
    store.a = _mint("uni-a.edu", IT_EMAIL)
    store.b = _mint("uni-b.edu", "it@uni-b.edu")
    store.bearer = {"Authorization": "Bearer ok"}
    store.base = f"/v1/institutions/licenses/{store.a}"
    store.audited = audited
    return store


def _staff_store(monkeypatch):
    """The dev caller: Semper staff, named by no licence; licence A
    (`store.a`) on uni-a.edu."""
    store = fake_firestore.install(monkeypatch)
    monkeypatch.setattr(repo.notify, "access_request", lambda *a, **k: None)
    monkeypatch.setattr(deps, "_DEV_USER", {**deps._DEV_USER, "email": "staff@semper.test"})
    store._data["users"] = {}
    store.a = _mint("uni-a.edu", IT_EMAIL)
    return store
