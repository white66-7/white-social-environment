package com.example.social_music.utils

object AnimationTemplates {
    const val ANIM_NONE = 0
    const val ANIM_SPINNER = 1
    const val ANIM_HEX = 2

    fun getDotSpinnerHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
        <style>
          * { box-sizing: border-box; }
          body {
            margin: 0; padding: 0; background: transparent; overflow: hidden;
            display: flex; justify-content: center; align-items: center; height: 100vh;
          }
          .spinner {
            position: relative;
            width: 60px;
            height: 60px;
            display: flex;
            justify-content: center;
            align-items: center;
            border-radius: 50%;
            margin-left: -75px;
          }

          .spinner span {
            position: absolute;
            top: 50%;
            left: var(--left);
            width: 35px;
            height: 7px;
            background: #ffff;
            animation: dominos 1s ease infinite;
            box-shadow: 2px 2px 3px 0px black;
          }

          .spinner span:nth-child(1) { --left: 80px; animation-delay: 0.125s; }
          .spinner span:nth-child(2) { --left: 70px; animation-delay: 0.3s; }
          .spinner span:nth-child(3) { left: 60px; animation-delay: 0.425s; }
          .spinner span:nth-child(4) { animation-delay: 0.54s; left: 50px; }
          .spinner span:nth-child(5) { animation-delay: 0.665s; left: 40px; }
          .spinner span:nth-child(6) { animation-delay: 0.79s; left: 30px; }
          .spinner span:nth-child(7) { animation-delay: 0.915s; left: 20px; }
          .spinner span:nth-child(8) { left: 10px; }

          @keyframes dominos {
            50% { opacity: 0.7; }
            75% { -webkit-transform: rotate(90deg); transform: rotate(90deg); }
            80% { opacity: 1; }
          }
        </style>
        </head>
        <body>
          <div class="spinner">
            <span></span><span></span><span></span><span></span>
            <span></span><span></span><span></span><span></span>
          </div>
        </body>
        </html>
        """.trimIndent()
    }

    fun getHexLoaderHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
        <style>
          * { box-sizing: border-box; }
          body {
            margin: 0; padding: 0; background: transparent; overflow: hidden;
            display: flex; justify-content: center; align-items: center; height: 100vh;
          }
          .socket {
            width: 200px; height: 200px; position: relative;
            transform: scale(0.62);
          }
          .hex-brick {
            background: #0F172A; width: 30px; height: 17px; position: absolute; top: 5px;
            animation: fade00 2s infinite; -webkit-animation: fade00 2s infinite;
          }
          .h2 { transform: rotate(60deg); -webkit-transform: rotate(60deg); }
          .h3 { transform: rotate(-60deg); -webkit-transform: rotate(-60deg); }
          .gel { height: 30px; width: 30px; position: absolute; top: 50%; left: 50%; }
          .center-gel {
            margin-left: -15px; margin-top: -15px;
            animation: pulse00 2s infinite; -webkit-animation: pulse00 2s infinite;
          }
          .c1 { margin-left: -47px; margin-top: -15px; }
          .c2 { margin-left: -31px; margin-top: -43px; }
          .c3 { margin-left: 1px; margin-top: -43px; }
          .c4 { margin-left: 17px; margin-top: -15px; }
          .c5 { margin-left: -31px; margin-top: 13px; }
          .c6 { margin-left: 1px; margin-top: 13px; }
          .c7 { margin-left: -63px; margin-top: -43px; }
          .c8 { margin-left: 33px; margin-top: -43px; }
          .c9 { margin-left: -15px; margin-top: 41px; }
          .c10 { margin-left: -63px; margin-top: 13px; }
          .c11 { margin-left: 33px; margin-top: 13px; }
          .c12 { margin-left: -15px; margin-top: -71px; }
          .c13 { margin-left: -47px; margin-top: -71px; }
          .c14 { margin-left: 17px; margin-top: -71px; }
          .c15 { margin-left: -47px; margin-top: 41px; }
          .c16 { margin-left: 17px; margin-top: 41px; }
          .c17 { margin-left: -79px; margin-top: -15px; }
          .c18 { margin-left: 49px; margin-top: -15px; }
          .c19 { margin-left: -63px; margin-top: -99px; }
          .c20 { margin-left: 33px; margin-top: -99px; }
          .c21 { margin-left: 1px; margin-top: -99px; }
          .c22 { margin-left: -31px; margin-top: -99px; }
          .c23 { margin-left: -63px; margin-top: 69px; }
          .c24 { margin-left: 33px; margin-top: 69px; }
          .c25 { margin-left: 1px; margin-top: 69px; }
          .c26 { margin-left: -31px; margin-top: 69px; }
          .c27 { margin-left: -79px; margin-top: -15px; }
          .c28 { margin-left: -95px; margin-top: -43px; }
          .c29 { margin-left: -95px; margin-top: 13px; }
          .c30 { margin-left: 49px; margin-top: 41px; }
          .c31 { margin-left: -79px; margin-top: -71px; }
          .c32 { margin-left: -111px; margin-top: -15px; }
          .c33 { margin-left: 65px; margin-top: -43px; }
          .c34 { margin-left: 65px; margin-top: 13px; }
          .c35 { margin-left: -79px; margin-top: 41px; }
          .c36 { margin-left: 49px; margin-top: -71px; }
          .c37 { margin-left: 81px; margin-top: -15px; }

          .r1 { animation: pulse00 2s infinite .2s; }
          .r2 { animation: pulse00 2s infinite .4s; }
          .r3 { animation: pulse00 2s infinite .6s; }
          .r1 > .hex-brick { animation: fade00 2s infinite .2s; }
          .r2 > .hex-brick { animation: fade00 2s infinite .4s; }
          .r3 > .hex-brick { animation: fade00 2s infinite .6s; }

          @keyframes pulse00 {
            0% { transform: scale(1); }
            50% { transform: scale(0.01); }
            100% { transform: scale(1); }
          }
          @keyframes fade00 {
            0% { background: #334155; }
            50% { background: #0F172A; }
            100% { background: #64748B; }
          }
        </style>
        </head>
        <body>
          <div class="socket">
            <div class="gel center-gel"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c1 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c2 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c3 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c4 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c5 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c6 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c7 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c8 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c9 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c10 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c11 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c12 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c13 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c14 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c15 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c16 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c17 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c18 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c19 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c20 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c21 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c22 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c23 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c24 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c25 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c26 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c27 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c28 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c29 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c30 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c31 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c32 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c33 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c34 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c35 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c36 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c37 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
          </div>
        </body>
        </html>
        """.trimIndent()
    }
}