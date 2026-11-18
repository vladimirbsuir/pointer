import unittest

from shop.inventory import Inventory, OutOfStock


class ReserveTests(unittest.TestCase):
    def test_a_failed_reservation_changes_no_stock(self):
        stock = Inventory({"a": 5, "b": 1})
        with self.assertRaises(OutOfStock):
            stock.reserve({"a": 2, "b": 3})
        self.assertEqual(stock.available("a"), 5)
        self.assertEqual(stock.available("b"), 1)

    def test_quantities_must_be_positive(self):
        stock = Inventory({"a": 5})
        with self.assertRaises(ValueError):
            stock.reserve({"a": 0})
        self.assertEqual(stock.available("a"), 5)

    def test_a_successful_reservation_takes_every_item(self):
        stock = Inventory({"a": 5, "b": 1})
        stock.reserve({"a": 2, "b": 1})
        self.assertEqual((stock.available("a"), stock.available("b")), (3, 0))
