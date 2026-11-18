from decimal import ROUND_HALF_UP, Decimal

TAX_RATE = Decimal("0.2")
TIERS = [(10, Decimal("0.05")), (50, Decimal("0.10")), (100, Decimal("0.15"))]


def cents(value):
    return Decimal(value).quantize(Decimal("0.01"))


def apply_discount(price, percent):
    discounted = Decimal(str(price)) * (100 - Decimal(str(percent))) / 100
    return discounted.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def tier_discount(quantity):
    rate = Decimal("0")
    for minimum, tier_rate in TIERS:
        if quantity >= minimum:
            rate = tier_rate
    return rate


def with_tax(amount):
    return cents(Decimal(amount) * (1 + TAX_RATE))
