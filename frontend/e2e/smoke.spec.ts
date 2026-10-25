import { expect, test } from '@playwright/test'

test('home lists tools and search filters them', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: /every tool you need/i })).toBeVisible()
  await expect(page.getByRole('link', { name: /Merge PDF Combine/ })).toBeVisible()
  await page.getByPlaceholder('Search tools…').fill('lock')
  await expect(page.getByRole('link', { name: /Unlock PDF/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Merge PDF Combine/ })).toHaveCount(0)
  await page.getByPlaceholder('Search tools…').fill('')
  await page.screenshot({ path: `e2e/.shots/home-${test.info().project.name}.png`, fullPage: true })
})
