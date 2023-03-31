package com.inokisheo.kotlinwebview.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.inokisheo.kotlinwebview.R
import com.inokisheo.kotlinwebview.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var viewModel: HomeViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_home, container, false)
        webView = root.findViewById(R.id.webview_home)
        progressBar = root.findViewById(R.id.progress_home)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        viewModel.url.observe(viewLifecycleOwner) { url ->
            webView.loadUrl(url)
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            //progressBar.isVisible = isLoading
        }
    }

    override fun onDestroyView() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroyView()
    }
}
