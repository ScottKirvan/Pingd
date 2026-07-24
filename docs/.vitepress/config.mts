import { defineConfig } from 'vitepress'

export default defineConfig({
  title: "UplinkStatus",
  description: "TODO: Replace with your project description.",
  base: '/UplinkStatus/',
  themeConfig: {
    nav: [
      { text: 'Home', link: '/' },
      { text: 'GitHub', link: 'https://github.com/ScottKirvan/UplinkStatus' }
    ],
    socialLinks: [
      { icon: 'github', link: 'https://github.com/ScottKirvan/UplinkStatus' },
      { icon: 'discord', link: 'https://discord.gg/TN6XJSNK5Y' }
    ],
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © Scott Kirvan'
    }
  }
})
