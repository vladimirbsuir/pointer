import datetime
import unittest

from shop.dates import business_days_between

MONDAY = datetime.date(2026, 3, 2)


class BusinessDaysTests(unittest.TestCase):
    def test_weekdays_are_counted_from_start_up_to_end(self):
        self.assertEqual(business_days_between(MONDAY, MONDAY + datetime.timedelta(days=7)), 5)
        self.assertEqual(business_days_between(MONDAY + datetime.timedelta(days=4), MONDAY + datetime.timedelta(days=7)), 1)
        self.assertEqual(business_days_between(MONDAY, MONDAY + datetime.timedelta(days=17)), 13)

    def test_an_empty_range_has_no_days_and_a_reversed_one_is_an_error(self):
        self.assertEqual(business_days_between(MONDAY, MONDAY), 0)
        with self.assertRaises(ValueError):
            business_days_between(MONDAY, MONDAY - datetime.timedelta(days=1))
