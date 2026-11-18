import unittest
from decimal import Decimal

from shop.cart import Cart


def cart_of(quantity):
    basket = Cart()
    basket.add("pen", "1.00", quantity)
    return basket


class CartTierTests(unittest.TestCase):
    def test_the_highest_tier_the_quantity_reaches_applies(self):
        self.assertEqual(cart_of(120).total(), Decimal("122.40"))
        self.assertEqual(cart_of(60).total(), Decimal("64.80"))
        self.assertEqual(cart_of(10).total(), Decimal("11.40"))
