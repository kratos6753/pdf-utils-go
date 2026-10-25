import { expect, test, type Page } from '@playwright/test'
import { pdf } from './fixtures'

async function pick(page: Page, files: string[]) {
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: /^Select/ }).click()
  await (await chooser).setFiles(files)
}

async function runAndDownload(page: Page, action: RegExp) {
  const button = page.getByRole('button', { name: action })
  await expect(button).toBeEnabled({ timeout: 15_000 }) // uploads finished
  await button.click()
  await expect(page.getByRole('heading', { name: 'Your file is ready!' })).toBeVisible({ timeout: 30_000 })
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: /^Download/ }).click()
  return (await download).suggestedFilename()
}

test('merge two PDFs and download the result', async ({ page }) => {
  await page.goto('/merge-pdf')
  await pick(page, [pdf('alpha', 2), pdf('beta', 3)])
  await expect(page.getByText('2 files')).toBeVisible()
  await expect(page.getByText('alpha.pdf')).toBeVisible()
  await page.screenshot({ path: 'e2e/.shots/merge-edit.png' })
  expect(await runAndDownload(page, /^Merge PDF/)).toBe('merged.pdf')
  await expect(page.getByRole('region', { name: 'Continue to' }).getByRole('link', { name: 'Compress PDF' })).toBeVisible()
  await page.screenshot({ path: 'e2e/.shots/merge-done.png' })
})

test('chain a result into another tool', async ({ page }) => {
  await page.goto('/merge-pdf')
  await pick(page, [pdf('alpha', 2), pdf('beta', 3)])
  await runAndDownload(page, /^Merge PDF/)
  await page.getByRole('region', { name: 'Continue to' }).getByRole('link', { name: 'Compress PDF' }).click()
  await expect(page.getByText('merged.pdf')).toBeVisible()
  expect(await runAndDownload(page, /^Compress PDF/)).toBe('merged_compressed.pdf')
})

test('protect validates matching passwords', async ({ page }) => {
  await page.goto('/protect-pdf')
  await pick(page, [pdf('secret', 1)])
  await page.getByPlaceholder('Enter password').fill('hunter22')
  await page.getByPlaceholder('Repeat password').fill('hunter23')
  await page.getByRole('button', { name: /^Protect PDF/ }).click()
  await expect(page.getByRole('alert')).toHaveText('Passwords do not match')
  await page.getByPlaceholder('Repeat password').fill('hunter22')
  expect(await runAndDownload(page, /^Protect PDF/)).toBe('secret_protected.pdf')
})

test('organize shows a page editor', async ({ page }) => {
  await page.goto('/organize-pdf')
  await pick(page, [pdf('pages', 4)])
  await expect(page.getByText('4 of 4 pages')).toBeVisible()
  await page.getByRole('button', { name: 'Delete page 2' }).click()
  await expect(page.getByText('3 of 4 pages')).toBeVisible()
  await page.screenshot({ path: 'e2e/.shots/organize.png' })
  expect(await runAndDownload(page, /^Save changes/)).toBe('pages_organized.pdf')
})

test('sign up, keep files in history, log out', async ({ page }) => {
  await page.goto('/compress-pdf')
  await pick(page, [pdf('report', 2)])
  await runAndDownload(page, /^Compress PDF/)

  await page.goto('/signup')
  await page.getByLabel('Name').fill('Playwright')
  await page.getByLabel('Email').fill(`pw+${Date.now()}@example.com`)
  await page.getByLabel('Password').fill('correct horse battery')
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByRole('heading', { name: /every tool you need/i })).toBeVisible()

  await page.goto('/history')
  await expect(page.getByText('report_compressed.pdf')).toBeVisible() // anonymous work was claimed
  await page.screenshot({ path: 'e2e/.shots/history.png' })

  await page.reload() // session survives reload via the refresh cookie
  await expect(page.getByText('report_compressed.pdf')).toBeVisible()

  await page.goto('/account')
  await expect(page.getByRole('heading', { name: 'Account settings' })).toBeVisible()
  await page.screenshot({ path: 'e2e/.shots/account.png', fullPage: true })
})
