class OutOfStock(Exception):
    pass


class Inventory:
    def __init__(self, stock):
        self.stock = dict(stock)

    def available(self, sku):
        return self.stock.get(sku, 0)

    def reserve(self, items):
        for sku, quantity in items.items():
            if quantity <= 0:
                raise ValueError("quantity must be positive")
            if self.available(sku) < quantity:
                raise OutOfStock(sku)
        for sku, quantity in items.items():
            self.stock[sku] -= quantity
