"""Floating-pool setup shared by test_licenses_seating.py and
test_seat_lease_counters.py: a uni licence's pool, a roster of approved Demo
accounts at its domain, and a member as the lease calls take one."""

import repo_view as repo


def _mint_floating(max_seats=2):
    return repo.create_institution_license(
        domain_lock="university.edu",
        admin_emails=["it@university.edu"],
        created_by_uid="admin",
        max_seats=max_seats,
        seating="floating",
    )


def _roster(store, *uids):
    store._data["users"] = {
        uid: {"email": f"{uid}@university.edu", "access_status": "APPROVED", "emailVerified": True, "mode": "demo"}
        for uid in uids
    }


def _user(store, uid):
    return {**store._data["users"][uid], "uid": uid}
