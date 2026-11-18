from decimal import Decimal

TAX_RATE = Decimal("0.2")
TIERS = [(10, Decimal("0.05")), (50, Decimal("0.10")), (100, Decimal("0.15"))]


def cents(value):
    return Decimal(value).quantize(Decimal("0.01"))


def apply_discount(price, percent):
    discounted = Decimal(str(price)) * (100 - Decimal(str(percent))) / 100
    return round(float(discounted), 2)


def tier_discount(quantity):
    for minimum, rate in TIERS:
        if quantity >= minimum:
            return rate
    return Decimal("0")


def with_tax(amount):
    return cents(Decimal(amount) * (1 + TAX_RATE))
