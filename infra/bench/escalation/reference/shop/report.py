from collections import defaultdict
from decimal import Decimal

from shop import dates


def monthly_summary(orders):
    totals = defaultdict(lambda: Decimal("0"))
    for order in orders:
        totals[dates.month_key(order["date"])] += Decimal(str(order["amount"]))
    return sorted(totals.items())
