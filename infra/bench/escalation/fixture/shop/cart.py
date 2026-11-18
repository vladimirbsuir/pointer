from decimal import Decimal

from shop import pricing


class Cart:
    def __init__(self):
        self.lines = []

    def add(self, sku, unit_price, quantity=1):
        if quantity <= 0:
            raise ValueError("quantity must be positive")
        self.lines.append((sku, Decimal(str(unit_price)), quantity))

    def quantity(self):
        return sum(quantity for _, _, quantity in self.lines)

    def subtotal(self):
        return sum((price * quantity for _, price, quantity in self.lines), Decimal("0"))

    def total(self):
        discount = pricing.tier_discount(self.quantity())
        return pricing.with_tax(self.subtotal() * (1 - discount))
