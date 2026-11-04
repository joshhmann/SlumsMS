import unittest
from model_forge_budgets import active_hours

class ForgeBudgetTest(unittest.TestCase):
    def test_funding_and_material_hunting_overlap(self):
        r=active_hours(30000000,3000000,900,300,.5,.5)
        self.assertEqual(r['funding_hours'],10);self.assertEqual(r['material_hours'],3)
        self.assertEqual(r['total_active_hours'],11)
    def test_saved_money_reduces_funding_but_not_material_work(self):
        r=active_hours(30000000,3000000,900,300,.5,.5,30000000)
        self.assertEqual(r['total_active_hours'],4)
    def test_invalid_income_or_nonfinite_inputs_are_rejected(self):
        for income in [0,-1,float('nan')]:
            with self.assertRaises(ValueError):active_hours(30,income,1,1,0,0)
