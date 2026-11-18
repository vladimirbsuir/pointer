import datetime
import unittest
from decimal import Decimal

from shop.report import monthly_summary


class MonthlySummaryTests(unittest.TestCase):
    def test_months_of_different_years_stay_apart_and_come_in_order(self):
        orders = [
            {"date": datetime.date(2026, 1, 5), "amount": "4.00"},
            {"date": datetime.date(2025, 12, 31), "amount": "2.50"},
            {"date": datetime.date(2025, 1, 10), "amount": "10"},
            {"date": datetime.date(2026, 1, 20), "amount": "1.25"},
        ]
        self.assertEqual(
            monthly_summary(orders),
            [("2025-01", Decimal("10")), ("2025-12", Decimal("2.50")), ("2026-01", Decimal("5.25"))],
        )

    def test_no_orders_give_an_empty_summary(self):
        self.assertEqual(monthly_summary([]), [])
